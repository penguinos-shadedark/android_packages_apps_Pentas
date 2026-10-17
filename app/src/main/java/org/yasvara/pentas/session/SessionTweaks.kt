/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.session

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.yasvara.pentas.data.AppSettings
import org.yasvara.pentas.data.SystemSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System settings a game session borrows (auto brightness, ringer, DND, three-finger
 * screenshot) and gives
 * back. The originals are persisted, so a process death mid-game still restores them on the
 * next session start.
 */
@Singleton
class SessionTweaks @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
    private val system: SystemSettings,
) {
    private val saved = context.getSharedPreferences("session_tweaks", Context.MODE_PRIVATE)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    fun apply() {
        restore()
        saved.edit {
            putBoolean(K_ACTIVE, true)
            if (settings.noAutoBrightness) {
                putBoolean(K_AUTO_BRIGHTNESS, system.autoBrightness)
                system.autoBrightness = false
            }
            if (settings.ringerMode != RINGER_KEEP) {
                putInt(K_RINGER, audio.ringerModeInternal)
                audio.ringerModeInternal = settings.ringerMode
            }
            if (settings.noThreeScreenshot) {
                putInt(K_THREE_SCREENSHOT, system.threeScreenshot)
                system.threeScreenshot = 0
            }
            if (settings.autoDnd && notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                putInt(K_DND, NotificationManager.INTERRUPTION_FILTER_ALL)
                notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            }
        }
    }

    fun restore() {
        if (!saved.getBoolean(K_ACTIVE, false)) return
        if (saved.contains(K_AUTO_BRIGHTNESS)) system.autoBrightness = saved.getBoolean(K_AUTO_BRIGHTNESS, true)
        if (saved.contains(K_RINGER)) audio.ringerModeInternal = saved.getInt(K_RINGER, AudioManager.RINGER_MODE_NORMAL)
        if (saved.contains(K_THREE_SCREENSHOT)) system.threeScreenshot = saved.getInt(K_THREE_SCREENSHOT, 0)
        if (saved.contains(K_DND)) notifications.setInterruptionFilter(saved.getInt(K_DND, NotificationManager.INTERRUPTION_FILTER_ALL))
        saved.edit { clear() }
    }

    private companion object {
        const val RINGER_KEEP = 3
        const val K_ACTIVE = "active"
        const val K_AUTO_BRIGHTNESS = "auto_brightness"
        const val K_RINGER = "ringer"
        const val K_DND = "dnd"
        const val K_THREE_SCREENSHOT = "three_screenshot"
    }
}
