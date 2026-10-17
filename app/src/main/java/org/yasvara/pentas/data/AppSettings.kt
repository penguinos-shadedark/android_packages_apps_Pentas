/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.yasvara.pentas.utils.ObservablePreferences

/** App preferences. Session switches are observable so the sidebar follows edits live. */
class AppSettings(context: Context) {
    private val db: SharedPreferences =
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

    val observable by lazy { ObservablePreferences(db) }

    /** Handle side (negative = left) and height. */
    var x by observable.int("offset_x", 1)
    var y by observable.int("offset_y", 200)

    var stayAwake by observable.boolean(KEY_STAY_AWAKE, false)
    val stayAwakeState get() = observable.booleanState(KEY_STAY_AWAKE, false)
    var noAutoBrightness by observable.boolean(KEY_AUTO_BRIGHTNESS_DISABLE, true)
    var autoDnd by observable.boolean(KEY_AUTO_DND, false)
    var danmaku by observable.boolean(KEY_DANMAKU, true)
    var noThreeScreenshot by observable.boolean(KEY_NO_THREE_SCREENSHOT, false)
    var lockBottomGesture by observable.boolean(KEY_LOCK_BOTTOM_GESTURE, false)
    var callsMode by observable.stringInt(KEY_CALLS_MODE, 0)
    var callOverlay by observable.boolean(KEY_CALL_OVERLAY, true)
    var memoryManagement by observable.boolean(KEY_MEMORY_MANAGEMENT, false)
    var cacheManagement by observable.boolean(KEY_CACHE_MANAGEMENT, true)
    /** AudioManager ringer mode for the session; 3 keeps the current one. */
    var ringerMode by observable.stringInt(KEY_RINGER_MODE, 3)

    var bannerSource by observable.string(KEY_BANNER_SOURCE, org.yasvara.pentas.data.banner.BannerSource.DEFAULT.key)
    var bannerAutoFetch by observable.boolean(KEY_BANNER_AUTO_FETCH, true)

    /** How faint the edge handle gets when idle, in percent. */
    var handleIdleAlpha by observable.intClamped(KEY_HANDLE_IDLE_ALPHA, 35, 5..100)
    val handleIdleAlphaState get() = observable.intClampedState(KEY_HANDLE_IDLE_ALPHA, 35, 5..100)

    var tileOrder: List<String>
        get() = list(KEY_TILE_ORDER)
        set(v) = db.edit { putString(KEY_TILE_ORDER, v.joinToString(",")) }

    var quickToggleOrder: List<String>
        get() = list(KEY_QUICK_TOGGLE_ORDER)
        set(v) = db.edit { putString(KEY_QUICK_TOGGLE_ORDER, v.joinToString(",")) }

    private fun list(key: String) = db.getString(key, "").orEmpty().split(',').filter { it.isNotEmpty() }

    fun getString(key: String, default: String): String = db.getString(key, default) ?: default
    fun putString(key: String, value: String) = db.edit { putString(key, value) }

    companion object {
        const val KEY_AUTO_BRIGHTNESS_DISABLE = "pentas_auto_brightness_disabled"
        const val KEY_STAY_AWAKE = "pentas_stay_awake"
        const val KEY_CALLS_MODE = "pentas_calls_mode"
        const val KEY_RINGER_MODE = "pentas_ringer_mode"
        const val KEY_LOCK_BOTTOM_GESTURE = "pentas_lock_bottom_gesture"
        const val KEY_AUTO_DND = "pentas_auto_dnd"
        const val KEY_DANMAKU = "pentas_danmaku"
        const val KEY_NO_THREE_SCREENSHOT = "pentas_no_three_screenshot"
        const val KEY_CALL_OVERLAY = "pentas_call_overlay"
        const val KEY_MEMORY_MANAGEMENT = "pentas_memory_management"
        const val KEY_CACHE_MANAGEMENT = "pentas_cache_management"
        const val KEY_HANDLE_IDLE_ALPHA = "pentas_handle_idle_alpha"
        const val KEY_BANNER_SOURCE = "pentas_banner_source"
        const val KEY_BANNER_AUTO_FETCH = "pentas_banner_auto_fetch"
        private const val KEY_TILE_ORDER = "tile_order"
        private const val KEY_QUICK_TOGGLE_ORDER = "quick_toggle_order"
    }
}
