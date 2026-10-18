/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.telemetry.network

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/** Latency to the game's servers: ICMP ping, with a TCP connect fallback. */
class NetworkPingMonitor {

    companion object {
        private const val TAG = "NetworkPingMonitor"
        private const val PING_INTERVAL_FAST_MS = 4_000L
        private const val PING_INTERVAL_SLOW_MS = 8_000L
        private const val PING_TIMEOUT_SEC = 1
        private const val MAX_SERVERS_TO_PING = 4
        private const val STABLE_THRESHOLD = 3        // good pings before slowing down
        private const val RE_RESOLVE_AFTER_FAILS = 3  // fails before re-scanning sockets

        /** Pre-compiled — zero allocation per parsed line. */
        private val WHITESPACE = Regex("\\s+")
        private val UID_FIELD = Regex("(?:^|\\s)uid[:=]\\s*(\\d+)(?:\\s|$)")

    }

    // ── Public state (consumed by Compose UI) ─────────────────────────

    /** Current best latency in ms. -1 = no connection / not started. */
    val latencyMs: MutableState<Int> = mutableStateOf(-1)

    /** All discovered servers and their latest RTT. */
    val allServerPings: MutableState<List<Pair<String, Int>>> = mutableStateOf(emptyList())

    // ── Internal state ────────────────────────────────────────────────

    private var monitorJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var generation = 0L
    private var cachedServerEndpoints = emptyList<Pair<String, Int>>()
    private var consecutiveFails = 0
    private var consecutiveGood = 0

    // ── Lifecycle ─────────────────────────────────────────────────────

    fun start(uid: Int) {
        stop()
        val runGeneration = generation
        cachedServerEndpoints = emptyList()
        consecutiveFails = 0
        consecutiveGood = 0
        Log.i(TAG, "Starting ping monitor for UID=$uid")

        monitorJob = scope.launch {
            // Resolve ONCE at session start
            val initialEndpoints = resolveServerEndpoints(uid)
            if (runGeneration != generation) return@launch
            cachedServerEndpoints = initialEndpoints
            Log.d(TAG, "Initial server endpoints for UID=$uid: $cachedServerEndpoints")

            while (isActive && runGeneration == generation) {
                try {
                    pollOnce(runGeneration, uid)
                } catch (e: Exception) {
                    Log.w(TAG, "Ping cycle error", e)
                    if (runGeneration == generation) {
                        latencyMs.value = -1
                        allServerPings.value = emptyList()
                    }
                }

                // Adaptive: fast when unstable, slow when stable
                delay(
                    if (consecutiveGood >= STABLE_THRESHOLD) PING_INTERVAL_SLOW_MS
                    else PING_INTERVAL_FAST_MS
                )
            }
        }
    }

    fun stop() {
        generation++
        monitorJob?.cancel()
        monitorJob = null
        latencyMs.value = -1
        allServerPings.value = emptyList()
        cachedServerEndpoints = emptyList()
        consecutiveFails = 0
        consecutiveGood = 0
        Log.i(TAG, "Ping monitor stopped")
    }

    // ── Poll cycle ────────────────────────────────────────────────────

    private fun pollOnce(runGeneration: Long, uid: Int) {
        if (runGeneration != generation) return
        // If no game sockets were found, retry discovery only after several polling
        // intervals instead of launching `ss` on every tick.
        if (cachedServerEndpoints.isEmpty() && consecutiveFails >= RE_RESOLVE_AFTER_FAILS) {
            val resolved = resolveServerEndpoints(uid)
            if (runGeneration != generation) return
            cachedServerEndpoints = resolved
            consecutiveFails = 0
        }

        if (cachedServerEndpoints.isEmpty()) {
            latencyMs.value = -1
            allServerPings.value = emptyList()
            consecutiveFails++
            return
        }

        val pings = cachedServerEndpoints
            .take(MAX_SERVERS_TO_PING)
            .map { (ip, port) -> ip to measurePing(ip, port) }
            .sortedBy { it.second.takeIf { p -> p > 0 } ?: Int.MAX_VALUE }

        if (runGeneration != generation) return
        allServerPings.value = pings
        val best = pings.firstOrNull { it.second > 0 }?.second ?: -1
        latencyMs.value = best

        if (best > 0) {
            consecutiveGood++
            consecutiveFails = 0
        } else {
            consecutiveGood = 0
            // Server IP may have rotated (load balancer) — re-resolve
            if (++consecutiveFails >= RE_RESOLVE_AFTER_FAILS) {
                val resolved = resolveServerEndpoints(uid)
                if (runGeneration != generation) return
                cachedServerEndpoints = resolved
                consecutiveFails = 0
            }
        }
    }

    // ── Server IP resolution ──────────────────────────────────────────

    /** Remote endpoints belonging to this game UID. Never fall back to global sockets, DNS, or gateway IPs. */
    private fun resolveServerEndpoints(uid: Int): List<Pair<String, Int>> {
        if (uid <= 0) return emptyList()
        val endpoints = LinkedHashSet<Pair<String, Int>>(8)
        var proc: Process? = null
        try {
            // Android's ss does not consistently support a UID filter; -e exposes UID
            // metadata, which parseSsLine checks before accepting any endpoint.
            proc = Runtime.getRuntime().exec(
                arrayOf("ss", "-e", "-t", "-n", "state", "established")
            )
            BufferedReader(InputStreamReader(proc.inputStream), 4096).use { reader ->
                reader.forEachLine { line -> parseSsLine(line, uid, endpoints) }
            }
            proc.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "UID-filtered ss scan failed for UID=$uid", e)
        } finally {
            proc?.let { runCatching { it.destroyForcibly() } }
        }
        return endpoints.toList()
    }

    /**
     * `ss -e -t -n state established` output differs by iproute2 version: some versions
     * include the state column, others omit it when a state filter is supplied. Locate the
     * peer accordingly, and require the explicit `uid:UID` metadata before accepting it.
     */
    private fun parseSsLine(line: String, uid: Int, out: MutableSet<Pair<String, Int>>) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("Netid") || trimmed.startsWith("State")) return
        if (UID_FIELD.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull() != uid) return
        val parts = trimmed.split(WHITESPACE)
        if (parts.size < 4) return
        val peerIndex = when {
            parts[0].toIntOrNull() != null -> 3 // state column omitted
            parts[0] in setOf("ESTAB", "ESTABLISHED") -> 4 // state column present
            parts[0] in setOf("tcp", "tcp4", "tcp6") -> 5 // netid and state present
            else -> return
        }
        val endpoint = extractEndpoint(parts.getOrNull(peerIndex) ?: return) ?: return
        if (!isLocalIp(endpoint.first)) out.add(endpoint)
    }

    /** Parse an ss peer token such as 203.0.113.7:443 or [2001:db8::1]:443. */
    private fun extractEndpoint(addr: String): Pair<String, Int>? {
        val host: String
        val portText: String
        if (addr.startsWith("[")) {
            val end = addr.indexOf(']')
            if (end <= 1 || end + 2 > addr.length || addr[end + 1] != ':') return null
            host = addr.substring(1, end)
            portText = addr.substring(end + 2)
        } else {
            val colon = addr.lastIndexOf(':')
            if (colon <= 0) return null
            host = addr.substring(0, colon)
            portText = addr.substring(colon + 1)
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val cleanHost = host.removePrefix("::ffff:")
        if (cleanHost.isBlank() || cleanHost == "*" || cleanHost == "0.0.0.0" || cleanHost == "::") return null
        return cleanHost to port
    }

    // ── Local IP filter ───────────────────────────────────────────────

    private fun isLocalIp(ip: String): Boolean = when {
        ip.startsWith("127.")    -> true   // loopback
        ip.startsWith("0.")      -> true
        ip == "0.0.0.0"         -> true
        ip == "*"               -> true
        ip.startsWith("169.254.") -> true  // link-local
        ip.startsWith("::1")      -> true  // IPv6 loopback
        ip.startsWith("fe80")     -> true  // IPv6 link-local
        ip.startsWith("fc")       -> true  // IPv6 ULA fc00::/7
        ip.startsWith("fd")       -> true
        else -> false
    }

    // ── Ping measurement ──────────────────────────────────────────────

    private fun measurePing(ip: String, port: Int): Int {
        val icmp = measureIcmpPing(ip)
        if (icmp > 0) return icmp

        // Fallback: TCP handshake to the actual remote port for firewalled game servers
        return try {
            val start = SystemClock.elapsedRealtime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 1000)
            }
            (SystemClock.elapsedRealtime() - start).toInt()
        } catch (_: Exception) {
            -1
        }
    }

    private fun measureIcmpPing(ip: String): Int {
        val cmd = if (ip.contains(':')) "ping6" else "ping"
        var proc: Process? = null
        return try {
            proc = Runtime.getRuntime().exec(
                arrayOf(cmd, "-c", "1", "-W", PING_TIMEOUT_SEC.toString(), ip)
            )
            var rtt = -1
            BufferedReader(InputStreamReader(proc.inputStream), 2048).use { r ->
                r.forEachLine { line ->
                    if (line.contains("time=")) {
                        rtt = line.substringAfter("time=")
                            .substringBefore(" ms")
                            .toFloatOrNull()?.toInt() ?: -1
                    }
                }
            }
            proc.waitFor()
            rtt
        } catch (e: Exception) {
            Log.w(TAG, "$cmd failed for $ip", e)
            -1
        } finally {
            proc?.destroy()
        }
    }
}
