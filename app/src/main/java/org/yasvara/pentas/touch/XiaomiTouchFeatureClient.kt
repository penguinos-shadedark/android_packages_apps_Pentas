/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.touch

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.ServiceManager
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.InvocationTargetException

/** Client for the vendor TouchFeature HAL, preferring the device HIDL 1.0 service. */
object XiaomiTouchFeatureClient {

    private const val TAG = "XiaomiTouchFeature"

    // ──────────────────────────────────────────────────────────────────
    // Service constants
    // ──────────────────────────────────────────────────────────────────

    private const val SERVICE_NAME = "vendor.xiaomi.hw.touchfeature.ITouchFeature/default"
    private const val INTERFACE_TOKEN = "vendor.xiaomi.hw.touchfeature.ITouchFeature"
    private const val HIDL_DESCRIPTOR = "vendor.xiaomi.hw.touchfeature@1.0::ITouchFeature"
    private const val HIDL_INSTANCE = "default"
    private const val LOOKUP_BACKOFF_MS = 30_000L

    /** AIDL transact codes (V3) */
    private const val TRANSACT_GET_MODE_CUR_VALUE = 1
    private const val TRANSACT_GET_MODE_DEFAULT_VALUE = 2
    private const val TRANSACT_GET_MODE_MAX_VALUE = 3
    private const val TRANSACT_GET_MODE_MIN_VALUE = 4
    private const val TRANSACT_GET_MODE_VALUE = 5
    private const val TRANSACT_GET_TOUCH_EVENT = 6
    private const val TRANSACT_SET_TOUCH_MODE = 9
    private const val TRANSACT_GET_MODE_WHITELIST = 11
    private const val TRANSACT_SET_MODE_PACKAGE_NAME = 14

    // ──────────────────────────────────────────────────────────────────
    // Touch Mode IDs — Complete list from RE
    // ──────────────────────────────────────────────────────────────────

    /** @section Basic Touch Modes (0-199) */
    const val MODE_WATERPROOF = 100                 // Water resistance sensitivity
    const val MODE_GLOVE = 101                      // Glove mode sensitivity
    const val MODE_SCP_TP_MISTOUCH = 103            // Mistouch prevention
    const val MODE_THP_SENSORHUB = 104              // Touch + sensor hub data
    const val MODE_TICKET_GRAB = 105                // Fast ticket grab mode
    const val MODE_TP_ACTIVE_TIME = 13              // Touch panel active scan interval (ms)

    /** @section Touch Control Modes — these match the kernel enum in
     *  touch-drivers/include/uapi/touch/xiaomi_touch.h exactly. Do not guess
     *  these values; a wrong id silently lands in the driver's `default:` branch
     *  and only shows up as a LOG_ERROR in dmesg. */
    const val MODE_GAME_MODE = 0                    // Touch_Game_Mode — gates the 240Hz path
    const val MODE_TUNE_TOUCH_UP_THRESHOLD = 2      // Touch-up threshold (default 2, 0 to 4)
    const val MODE_TUNE_JITTER_TOLERANCE = 3        // Jitter tolerance (default 2, 0 to 4)
    const val MODE_TUNE_AIM_SENSITIVITY = 4         // Aim sensitivity (default 2, 0 to 4)
    const val MODE_TUNE_TAP_STABILITY = 5           // Tap stability (default 2, 0 to 4)
    const val MODE_EXPERT_PRESET = 6                // Touch_Expert_Mode (1..3, default 1)
    const val MODE_TUNE_EDGE_FILTER = 7             // Edge filter (default 2, 0 to 3)
    const val MODE_PANEL_ORIENTATION = 8            // Panel orientation (0..3)
    const val MODE_SUPER_REPORT_RATE = 202          // Touch_Super_Report — see setReportRateBoost

    /** Live panel scan rate in Hz, computed by the HAL. */
    const val MODE_REPORT_RATE = 1011

    const val MODE_TICKET_GRAB_BOOST = 1028         // Ticket grab touch boost
    const val MODE_DYNAMIC_RATE_APPS = 1085         // HTSR: High Touch Sampling Rate
    const val MODE_HOT_AREA_SECTION = 1097          // Game UI hot area section params
    const val MODE_PROXIMITY_MODE = 1106            // Proximity-based touch on/off
    const val MODE_HOT_AREA_FREEFORM = 1109         // Freeform window touch hotspot
    const val MODE_TICKET_GRAB_MODE = 1116          // Ticket grab touch mode
    const val MODE_GAME_UI_LIST = 1118              // Game UI list scene params

    /** @section Game Control Modes (10000-10199) */
    const val MODE_GAME_SUPER_CORE = 10001          // SuperCore: frame boost/duration/buffer
    const val MODE_GAME_GAMEPAD = 10007             // Gamepad: highlight/multi-device
    const val MODE_GAME_PACKAGE_NAME = 10100        // Set current game package (use setModePackageName)
    const val MODE_GAME_START = 10101               // Game start (1) / stop (0)
    const val MODE_SUPER_TOUCH_APP = 10103          // Super touch whitelist (use setModePackageName)
    const val MODE_PENDING_BUFFER_APP = 10104       // Pending buffer whitelist (use setModePackageName)
    const val MODE_SUPER_TOUCH_LEVEL = 10105        // Super touch sensitivity level (0-100)
    const val MODE_PENDING_BUFFER_LEVEL = 10106     // Pending buffer level (0-100)
    const val MODE_GAME_SCENE = 10109               // Game scene change (loading/battle/menu)
    const val MODE_GAME_4D_SCENE = 10110            // 4D vibration scene ID
    const val MODE_NORMAL_REPORT_RATE = 10111       // Normal report rate enable
    const val MODE_NORMAL_REPORT_RATE_APP = 10112   // Normal report rate app list (use setModePackageName)

    /** @section Hot Area Modes (10500-10599) */
    const val MODE_HOT_AREA_APP_SET = 10501         // Hot area app set enable
    const val MODE_HOT_AREA_APP_SET_LIST = 10502    // Hot area app set list (use setModePackageName)
    const val MODE_HOT_AREA_ADAPT = 10503           // Hot area adaptive enable
    const val MODE_HOT_AREA_ADAPT_LIST = 10504      // Hot area adaptive list (use setModePackageName)

    /** @section Vibrator Modes (10600-10699) */
    const val MODE_VIBRATOR = 10602                 // Game vibrator enable
    const val MODE_VIBRATOR_APP = 10603             // Game vibrator app list (use setModePackageName)

    /** @section Pencil Modes (20000+) */
    const val MODE_PENCIL_POSTURE = 20036           // Pencil posture control

    // ──────────────────────────────────────────────────────────────────
    // SuperCore sub-values (sent as value param to MODE_GAME_SUPER_CORE)
    // ──────────────────────────────────────────────────────────────────

    const val SUPER_CORE_FIRSTFRAME_DURATION_ON = 101
    const val SUPER_CORE_FIRSTFRAME_DURATION_OFF = 102
    const val SUPER_CORE_FIRSTFRAME_DROPBUFFER_ON = 103
    const val SUPER_CORE_FIRSTFRAME_DROPBUFFER_OFF = 104
    const val SUPER_CORE_DYNAMIC_DURATION_ON = 105
    const val SUPER_CORE_DYNAMIC_DURATION_OFF = 106
    const val SUPER_CORE_DYNAMIC_DROPBUFFER_ON = 107
    const val SUPER_CORE_DYNAMIC_DROPBUFFER_OFF = 108
    const val SUPER_CORE_SUPERTOUCH_ON = 109
    const val SUPER_CORE_SUPERTOUCH_OFF = 110
    const val SUPER_CORE_FIRSTFRAME_BOOST_ON = 111
    const val SUPER_CORE_FIRSTFRAME_BOOST_OFF = 112
    const val SUPER_CORE_DYNAMIC_BOOST_ON = 113
    const val SUPER_CORE_DYNAMIC_BOOST_OFF = 114
    const val SUPER_CORE_PENDINGBUFFER_ON = 115
    const val SUPER_CORE_PENDINGBUFFER_OFF = 116
    const val SUPER_CORE_VSYNCBUFFER_ON = 117
    const val SUPER_CORE_VSYNCBUFFER_OFF = 118

    // ──────────────────────────────────────────────────────────────────
    // Gamepad sub-values (sent as value param to MODE_GAME_GAMEPAD)
    // ──────────────────────────────────────────────────────────────────

    const val GAMEPAD_ENABLE = 701
    const val GAMEPAD_DISABLE = 702
    const val GAMEPAD_HIGHLIGHT_ON = 703
    const val GAMEPAD_HIGHLIGHT_OFF = 704
    const val GAMEPAD_AIPREDICT_ON = 705
    const val GAMEPAD_AIPREDICT_OFF = 706
    const val GAMEPAD_MULTIDEVICE_ON = 707
    const val GAMEPAD_MULTIDEVICE_OFF = 708

    // ──────────────────────────────────────────────────────────────────
    // Hot Area sub-values
    // ──────────────────────────────────────────────────────────────────

    const val HOT_AREA_ENABLE = 501
    const val HOT_AREA_DISABLE = 502

    // ──────────────────────────────────────────────────────────────────
    // Primary display
    // ──────────────────────────────────────────────────────────────────

    private const val DISPLAY_PRIMARY = 0

    // ──────────────────────────────────────────────────────────────────
    // Capability model
    // ──────────────────────────────────────────────────────────────────

    /** `Touch_Mode_NUM`: the driver ignores modes at or above it. */
    private const val KERNEL_MODE_NUM = 38

    /** Modes above [KERNEL_MODE_NUM] the HAL acts on itself; verified on NT36532. */
    private val HAL_CONSUMED_MODES = setOf(
        MODE_SUPER_REPORT_RATE,
        MODE_REPORT_RATE,
        MODE_GAME_START,
        MODE_GAME_PACKAGE_NAME,
    )

    /** Whether [modeId] has any effect on this device; the HAL reports success either way. */
    fun isModeSupported(modeId: Int): Boolean =
        (modeId < KERNEL_MODE_NUM || modeId in HAL_CONSUMED_MODES) &&
            (modeId != MODE_GAME_PACKAGE_NAME || getHidlService() == null || getAidlService() != null)

    // ──────────────────────────────────────────────────────────────────
    // Cached service handles
    // ──────────────────────────────────────────────────────────────────

    @Volatile private var cachedBinder: IBinder? = null
    @Volatile private var nextAidlLookupMs = 0L
    @Volatile private var cachedHidlBinder: Any? = null
    @Volatile private var nextHidlLookupMs = 0L
    @Volatile private var packageApiWarningLogged = false

    /** The AIDL V3 service exists on newer vendor stacks; this Marble build exposes HIDL 1.0. */
    private fun getAidlService(): IBinder? {
        cachedBinder?.let {
            if (it.isBinderAlive) return it
            cachedBinder = null
        }
        val now = SystemClock.elapsedRealtime()
        if (now < nextAidlLookupMs) return null
        val service = try {
            ServiceManager.getService(SERVICE_NAME)
        } catch (_: SecurityException) {
            null
        }
        if (service == null) {
            nextAidlLookupMs = now + LOOKUP_BACKOFF_MS
            return null
        }
        cachedBinder = service
        nextAidlLookupMs = 0L
        try {
            service.linkToDeath({ cachedBinder = null }, 0)
        } catch (_: RemoteException) {
            cachedBinder = null
        }
        return cachedBinder
    }

    /** Resolve HIDL without waiting indefinitely on devices that only ship the AIDL service. */
    private fun getHidlService(): Any? {
        cachedHidlBinder?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (now < nextHidlLookupMs) return null
        return try {
            val hwBinder = Class.forName("android.os.HwBinder")
            val service = hwBinder.getMethod(
                "getService", String::class.java, String::class.java, Boolean::class.javaPrimitiveType
            ).invoke(null, HIDL_DESCRIPTOR, HIDL_INSTANCE, false)
            if (service == null) {
                nextHidlLookupMs = now + LOOKUP_BACKOFF_MS
                null
            } else {
                cachedHidlBinder = service
                nextHidlLookupMs = 0L
                service
            }
        } catch (_: Exception) {
            nextHidlLookupMs = now + LOOKUP_BACKOFF_MS
            null
        }
    }

    /**
     * Invoke one HIDL 1.0 transaction using the platform HwParcel classes reflectively.
     * Reflection keeps the app build independent of a generated vendor Java stub.
     */
    private fun <T> transactHidl(
        code: Int,
        writeArgs: (Class<*>, Any) -> Unit,
        readReply: (Class<*>, Any) -> T,
    ): T? {
        val binder = getHidlService() ?: return null
        var request: Any? = null
        var reply: Any? = null
        var parcelClass: Class<*>? = null
        return try {
            parcelClass = Class.forName("android.os.HwParcel")
            request = parcelClass.getConstructor().newInstance()
            reply = parcelClass.getConstructor().newInstance()
            parcelClass.getMethod("writeInterfaceToken", String::class.java)
                .invoke(request, HIDL_DESCRIPTOR)
            writeArgs(parcelClass, request)
            Class.forName("android.os.IHwBinder").getMethod(
                "transact", Int::class.javaPrimitiveType, parcelClass, parcelClass,
                Int::class.javaPrimitiveType
            ).invoke(binder, code, request, reply, 0)
            parcelClass.getMethod("verifySuccess").invoke(reply)
            parcelClass.getMethod("releaseTemporaryStorage").invoke(request)
            readReply(parcelClass, reply)
        } catch (e: Exception) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            Log.w(TAG, "HIDL transaction $code failed", cause)
            cachedHidlBinder = null
            nextHidlLookupMs = SystemClock.elapsedRealtime() + LOOKUP_BACKOFF_MS
            null
        } finally {
            parcelClass?.let { clazz ->
                request?.let { runCatching { clazz.getMethod("release").invoke(it) } }
                reply?.let { runCatching { clazz.getMethod("release").invoke(it) } }
            }
        }
    }

    private fun hidlInt(code: Int, vararg args: Int): Int? = transactHidl(
        code,
        writeArgs = { parcel, request ->
            val writeInt = parcel.getMethod("writeInt32", Int::class.javaPrimitiveType)
            args.forEach { writeInt.invoke(request, it) }
        },
        readReply = { parcel, reply -> parcel.getMethod("readInt32").invoke(reply) as Int },
    )

    /** Whether either supported transport is available. */
    val isAvailable: Boolean
        get() = getHidlService() != null || getAidlService() != null

    // ──────────────────────────────────────────────────────────────────
    // Core API
    // ──────────────────────────────────────────────────────────────────

    fun setTouchMode(modeId: Int, value: Int): Boolean =
        setTouchMode(DISPLAY_PRIMARY, modeId, value)

    /** Sets [modeId] on [displayId]; true when the HAL call succeeded. */
    fun setTouchMode(displayId: Int, modeId: Int, value: Int): Boolean {
        // Prefer HIDL on this device; HIDL 1.0 setModeValue is transaction 8.
        if (getHidlService() != null) return hidlInt(8, displayId, modeId, value) == 0
        val binder = getAidlService() ?: return false

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(displayId)
            data.writeInt(modeId)
            data.writeInt(value)
            binder.transact(TRANSACT_SET_TOUCH_MODE, data, reply, 0)
            reply.readException()
            val result = reply.readInt()
            if (result != 0) {
                Log.w(TAG, "setTouchMode($modeId, $value) failed: ret=$result")
                false
            } else true
        } catch (e: RemoteException) {
            Log.e(TAG, "setTouchMode($modeId, $value) transact failed", e)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Getter transaction IDs 1–4 are shared by AIDL V3 and HIDL 1.0. */
    private fun getModeInt(transactCode: Int, displayId: Int, modeId: Int): Int {
        if (getHidlService() != null) return hidlInt(transactCode, displayId, modeId) ?: -1
        val binder = getAidlService() ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(displayId)
            data.writeInt(modeId)
            if (binder.transact(transactCode, data, reply, 0)) {
                reply.readException()
                reply.readInt()
            } else -1
        } catch (e: RemoteException) {
            Log.e(TAG, "Transact $transactCode failed for mode $modeId", e)
            -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun getModeCurValue(modeId: Int): Int =
        getModeInt(TRANSACT_GET_MODE_CUR_VALUE, DISPLAY_PRIMARY, modeId)

    /** Get an integer array for a specific mode value query. */
    fun getModeValue(modeId: Int): IntArray? {
        if (getHidlService() != null) {
            val values = transactHidl(
                5,
                writeArgs = { parcel, request ->
                    val writeInt = parcel.getMethod("writeInt32", Int::class.javaPrimitiveType)
                    writeInt.invoke(request, DISPLAY_PRIMARY)
                    writeInt.invoke(request, modeId)
                },
                readReply = { parcel, reply ->
                    @Suppress("UNCHECKED_CAST")
                    (parcel.getMethod("readInt32Vector").invoke(reply) as? List<Int>)?.toIntArray()
                },
            )
            return values
        }
        val binder = getAidlService() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(DISPLAY_PRIMARY)
            data.writeInt(modeId)
            if (binder.transact(TRANSACT_GET_MODE_VALUE, data, reply, 0)) {
                reply.readException()
                reply.createIntArray()
            } else null
        } catch (e: RemoteException) {
            Log.e(TAG, "getModeValue failed for mode $modeId", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Package-name APIs are available only on the newer AIDL V3 interface. */
    fun setModePackageName(modeId: Int, packageName: String): Boolean {
        if (getHidlService() != null) {
            if (!packageApiWarningLogged) {
                packageApiWarningLogged = true
                Log.w(TAG, "HIDL 1.0 does not expose setModePackageName; package lists are unavailable")
            }
            return false
        }
        val binder = getAidlService() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(DISPLAY_PRIMARY)
            data.writeInt(modeId)
            data.writeString(packageName)
            binder.transact(TRANSACT_SET_MODE_PACKAGE_NAME, data, reply, 0)
            reply.readException()
            true
        } catch (e: RemoteException) {
            Log.e(TAG, "setModePackageName($modeId) transact failed", e)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** HIDL 1.0 has no touch-event query; keep this AIDL-only. */
    fun getTouchEvent(): String? {
        val binder = getAidlService() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            binder.transact(TRANSACT_GET_TOUCH_EVENT, data, reply, 0)
            reply.readException()
            reply.readString()
        } catch (e: RemoteException) {
            Log.e(TAG, "getTouchEvent failed", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** HIDL 1.0 has no whitelist query; keep this AIDL-only. */
    fun getModeWhitelist(vararg modeIds: Int): String? {
        val binder = getAidlService() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(DISPLAY_PRIMARY)
            data.writeIntArray(modeIds)
            binder.transact(TRANSACT_GET_MODE_WHITELIST, data, reply, 0)
            reply.readException()
            reply.readString()
        } catch (e: RemoteException) {
            Log.e(TAG, "getModeWhitelist failed", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // High-level convenience methods
    // ──────────────────────────────────────────────────────────────────

    /**
     * Activate full game touch mode for a package.
     * Equivalent to what Joyose does when a game starts.
     */
    fun onGameStart(packageName: String) {
        setModePackageName(MODE_GAME_PACKAGE_NAME, packageName)
        setTouchMode(MODE_GAME_START, 1)
        // Enable HTSR (high touch sampling rate)
        setTouchMode(MODE_DYNAMIC_RATE_APPS, 1)
        Log.i(TAG, "Game touch mode activated: $packageName")
    }

    /**
     * Deactivate game touch mode.
     * Equivalent to what Joyose does when a game stops.
     */
    fun onGameStop() {
        setTouchMode(MODE_GAME_START, 0)
        setTouchMode(MODE_DYNAMIC_RATE_APPS, 0)
        Log.i(TAG, "Game touch mode deactivated")
    }

    /**
     * Set game scene (loading screen, battle, menu, etc.)
     * Adjusts touch sensitivity based on gameplay context.
     */
    fun setGameScene(sceneId: Int) {
        setTouchMode(MODE_GAME_SCENE, sceneId)
    }

    /**
     * Enable/disable freeform window touch mode.
     * Adjusts touch zones when game is in split/freeform window.
     */
    fun setFreeformMode(enabled: Boolean) {
        setTouchMode(MODE_HOT_AREA_FREEFORM, if (enabled) 1 else 0)
    }

    /**
     * Set game 4D vibration scene.
     */
    fun set4DScene(sceneId: Int) {
        setTouchMode(MODE_GAME_4D_SCENE, sceneId)
    }

}
