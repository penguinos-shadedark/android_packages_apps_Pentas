/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.thermal

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap

/**
 * sysfs/procfs I/O with cached file descriptors and pread/pwrite at offset 0: no open/close
 * or allocation per access, one reopen on EIO/EBADF. Thread-safe per node.
 */
object KernelNodeIO {

    private const val TAG = "KernelNodeIO"
    private const val READ_BUF_SIZE = 4096
    private const val OPEN_BACKOFF_MS = 15_000L

    /** A cached, pre-opened node handle. */
    private class NodeHandle(val path: String, val writable: Boolean) {
        var fd: FileDescriptor? = null
        var nextOpenAttemptMs = 0L
        val buf = ByteArray(READ_BUF_SIZE)

        fun ensureOpen(): FileDescriptor? {
            fd?.let { return it }
            val now = SystemClock.elapsedRealtime()
            if (now < nextOpenAttemptMs) return null
            return try {
                val flags = if (writable) OsConstants.O_RDWR else OsConstants.O_RDONLY
                Os.open(path, flags, 0).also {
                    fd = it
                    nextOpenAttemptMs = 0L
                }
            } catch (_: Exception) {
                // Missing/denied sysfs nodes must not be reopened on every telemetry tick.
                nextOpenAttemptMs = now + OPEN_BACKOFF_MS
                null
            }
        }

        fun close() {
            fd?.let { runCatching { Os.close(it) } }
            fd = null
        }
    }

    private val readHandles = ConcurrentHashMap<String, NodeHandle>()
    private val writeHandles = ConcurrentHashMap<String, NodeHandle>()

    /** Nodes that already logged a failure — avoids logspam on absent hardware. */
    private val warned = ConcurrentHashMap.newKeySet<String>()

    fun exists(path: String): Boolean = File(path).exists()

    /**
     * Read the node content as a trimmed string, or null on failure.
     * Zero allocation beyond the returned String.
     */
    fun read(path: String): String? {
        val handle = readHandles.getOrPut(path) { NodeHandle(path, writable = false) }
        synchronized(handle) {
            repeat(2) { attempt ->
                val fd = handle.ensureOpen() ?: return failOnce(path, "open for read")
                try {
                    val n = Os.pread(fd, handle.buf, 0, READ_BUF_SIZE, 0)
                    if (n <= 0) return null
                    return String(handle.buf, 0, n).trim()
                } catch (e: Exception) { // ErrnoException | InterruptedIOException
                    handle.close()
                    if (attempt == 1) return failOnce(path, "pread failed: $e")
                }
            }
        }
        return null
    }

    /** Read the node as a Long (handles trailing newline/whitespace). */
    fun readLong(path: String): Long? = read(path)?.toLongOrNull()

    /** Read the node as an Int. */
    fun readInt(path: String): Int? = read(path)?.toIntOrNull()

    /**
     * Write a string to the node. Returns true on success.
     * Retries exactly once with a fresh fd on EIO/EBADF/ENODEV.
     */
    fun write(path: String, value: String): Boolean {
        val handle = writeHandles.getOrPut(path) { NodeHandle(path, writable = true) }
        val bytes = value.toByteArray()
        synchronized(handle) {
            repeat(2) { attempt ->
                val fd = handle.ensureOpen() ?: run {
                    failOnce(path, "open for write")
                    return false
                }
                try {
                    Os.pwrite(fd, bytes, 0, bytes.size, 0)
                    return true
                } catch (e: Exception) { // ErrnoException | InterruptedIOException
                    handle.close()
                    if (attempt == 1) {
                        failOnce(path, "pwrite '$value' failed: $e")
                        return false
                    }
                }
            }
        }
        return false
    }

    fun write(path: String, value: Long): Boolean = write(path, value.toString())

    fun write(path: String, value: Int): Boolean = write(path, value.toString())

    fun write(path: String, enabled: Boolean): Boolean = write(path, if (enabled) "1" else "0")

    /**
     * Read a space/newline separated list of Longs (e.g. scaling_available_frequencies,
     * kgsl gpu_available_frequencies).
     */
    fun readLongList(path: String): List<Long> =
        read(path)?.split(' ', '\n', '\t')?.mapNotNull { it.toLongOrNull() } ?: emptyList()

    /** Read a whitespace-separated token list (e.g. available_governors). */
    fun readTokenList(path: String): List<String> =
        read(path)?.split(' ', '\n', '\t')?.filter { it.isNotBlank() } ?: emptyList()

    /** Drop all cached descriptors (e.g. after a .ko reload event). */
    fun invalidateAll() {
        readHandles.values.forEach { synchronized(it) { it.close() } }
        writeHandles.values.forEach { synchronized(it) { it.close() } }
        warned.clear()
    }

    private fun failOnce(path: String, what: String): Nothing? {
        if (warned.add(path)) Log.w(TAG, "$what failed: $path")
        return null
    }
}
