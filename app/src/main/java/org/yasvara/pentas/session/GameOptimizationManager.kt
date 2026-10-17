/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.session

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.storage.StorageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.yasvara.pentas.data.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Launch-time housekeeping for a game: drop background apps to free RAM and clear the game's
 * cache once it grows past [CACHE_THRESHOLD]. Each half is its own switch in settings.
 */
@Singleton
class GameOptimizationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun optimizeGameLaunch(pkg: String) {
        if (!settings.memoryManagement && !settings.cacheManagement) return
        scope.launch {
            if (settings.memoryManagement) runCatching { clearBackgroundApps(pkg) }.onFailure { Log.w(TAG, "memory", it) }
            if (settings.cacheManagement) runCatching { clearCacheIfLarge(pkg) }.onFailure { Log.w(TAG, "cache", it) }
        }
    }

    private fun clearBackgroundApps(game: String) {
        val am = context.getSystemService(ActivityManager::class.java)
        val keep = setOf(game, context.packageName, "android")
        am.runningAppProcesses.orEmpty()
            .filter { it.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
            .flatMap { it.pkgList.asList() }
            .distinct()
            .filter { it !in keep }
            .forEach { am.killBackgroundProcesses(it) }
    }

    private fun clearCacheIfLarge(pkg: String) {
        val stats = context.getSystemService(StorageStatsManager::class.java)
        val cache = stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle()).cacheBytes
        if (cache <= CACHE_THRESHOLD) return
        val observer = Class.forName("android.content.pm.IPackageDataObserver")
        PackageManager::class.java
            .getMethod("deleteApplicationCacheFiles", String::class.java, observer)
            .invoke(context.packageManager, pkg, null)
        Log.i(TAG, "Cleared ${cache shr 20} MB cache for $pkg")
    }

    private companion object {
        const val TAG = "GameOptimization"
        const val CACHE_THRESHOLD = 100L * 1024 * 1024
    }
}
