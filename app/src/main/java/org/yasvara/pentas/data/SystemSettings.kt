/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.UserHandle
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The few platform settings Pentas reads or writes, plus the platform game list. */
@Singleton
class SystemSettings @Inject constructor(@ApplicationContext context: Context) {
    private val resolver = context.contentResolver

    var autoBrightness: Boolean
        get() = Settings.System.getIntForUser(
            resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC, UserHandle.USER_CURRENT,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        set(auto) {
            Settings.System.putIntForUser(
                resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                UserHandle.USER_CURRENT,
            )
        }

    /** Three-finger screenshot gesture, 0 when off. */
    var threeScreenshot: Int
        get() = Settings.System.getIntForUser(resolver, KEY_THREE_SCREENSHOT, 0, UserHandle.USER_CURRENT)
        set(value) { Settings.System.putIntForUser(resolver, KEY_THREE_SCREENSHOT, value, UserHandle.USER_CURRENT) }

    /**
     * Games the sidebar opens for. system_server's GameSpaceService reads the same key and
     * keeps only `package=mode` entries, so every write goes through [write].
     */
    val games: List<String> get() = entries().keys.toList()

    /** Adds an app the user picked and lifts an earlier removal. */
    fun addGame(pkg: String) {
        val list = entries()
        if (pkg !in list) {
            list[pkg] = MODE_STANDARD
            write(list)
        }
        if (pkg in denied) denied = denied - pkg
    }

    /** Removes [pkg] and remembers it, so auto-detection does not add it back. */
    fun removeGame(pkg: String) {
        val list = entries()
        if (list.remove(pkg) != null) write(list)
        if (pkg !in denied) denied = denied + pkg
    }

    /** Folds installed games into the list, skipping the ones the user removed. */
    fun syncDetected(pm: PackageManager) {
        if (!autoGameDetect) return
        val list = entries()
        val skip = denied
        val fresh = runCatching {
            pm.getInstalledApplications(0)
                .filter { AppClassifier.isGame(it) && pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { it.packageName }
        }.getOrDefault(emptyList()).filter { it !in list && it !in skip }
        if (fresh.isEmpty()) return
        fresh.forEach { list[it] = MODE_PERFORMANCE }
        write(list)
    }

    var autoGameDetect: Boolean
        get() = Settings.System.getIntForUser(resolver, KEY_AUTO_DETECT, 1, UserHandle.USER_CURRENT) == 1
        set(on) { Settings.System.putIntForUser(resolver, KEY_AUTO_DETECT, if (on) 1 else 0, UserHandle.USER_CURRENT) }

    private fun entries(): LinkedHashMap<String, Int> {
        val list = LinkedHashMap<String, Int>()
        Settings.System.getStringForUser(resolver, KEY_GAME_LIST, UserHandle.USER_CURRENT).orEmpty()
            .split(';').forEach { entry ->
                val pkg = entry.substringBefore('=').trim()
                if (pkg.isNotEmpty()) list[pkg] = entry.substringAfter('=', "").trim().toIntOrNull() ?: MODE_STANDARD
            }
        return list
    }

    private fun write(list: Map<String, Int>) {
        Settings.System.putStringForUser(
            resolver, KEY_GAME_LIST, list.entries.joinToString(";") { "${it.key}=${it.value}" }, UserHandle.USER_CURRENT,
        )
    }

    private var denied: Set<String>
        get() = Settings.System.getStringForUser(resolver, KEY_DENIED_LIST, UserHandle.USER_CURRENT)
            .orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        set(value) {
            Settings.System.putStringForUser(resolver, KEY_DENIED_LIST, value.joinToString(";"), UserHandle.USER_CURRENT)
        }

    companion object {
        const val KEY_GAME_LIST = "gamespace_game_list"
        const val KEY_DENIED_LIST = "gamespace_denied_list"
        const val KEY_AUTO_DETECT = "gamespace_auto_game_detect"
        private const val KEY_THREE_SCREENSHOT = "three_finger_gesture"

        /** GameManager game modes, as GameSpaceService stores them. */
        private const val MODE_STANDARD = 1
        private const val MODE_PERFORMANCE = 2
    }
}
