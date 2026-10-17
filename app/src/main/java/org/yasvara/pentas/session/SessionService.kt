/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.session

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.yasvara.pentas.audio.DolbyBridge
import org.yasvara.pentas.audio.DolbyProfileClient
import org.yasvara.pentas.charging.ChargingProfileRepository
import org.yasvara.pentas.data.PerAppSettingStore
import org.yasvara.pentas.data.SettingsIO
import org.yasvara.pentas.data.fpsstats.FpsStatsCollector
import org.yasvara.pentas.data.fpsstats.FpsStatsRepository
import org.yasvara.pentas.overlay.OverlayState
import org.yasvara.pentas.overlay.Sidebar
import org.yasvara.pentas.overlay.monitor.WidgetOverlay
import org.yasvara.pentas.overlay.tiles.TileRegistry
import org.yasvara.pentas.session.danmaku.DanmakuOverlay
import org.yasvara.pentas.telemetry.FpsInteractor
import org.yasvara.pentas.telemetry.network.NetworkTelemetry
import org.yasvara.pentas.telemetry.network.WifiLowLatencyLock
import org.yasvara.pentas.thermal.PerfTuner
import org.yasvara.pentas.touch.GameTouchModeManager
import javax.inject.Inject

/**
 * One bound session per foreground game or video app. system_server's PentasService binds
 * `pentas://session/<package>?mode=<n>`; the bind keeps this process at foreground priority
 * with no notification, and unbinding ends the session.
 */
@AndroidEntryPoint(Service::class)
class SessionService : Hilt_SessionService() {
    @Inject lateinit var session: ActiveSessionStore
    @Inject lateinit var state: OverlayState
    @Inject lateinit var sidebar: Sidebar
    @Inject lateinit var tiles: TileRegistry
    @Inject lateinit var widgets: WidgetOverlay
    @Inject lateinit var tweaks: SessionTweaks
    @Inject lateinit var optimizer: GameOptimizationManager
    @Inject lateinit var danmaku: DanmakuOverlay
    @Inject lateinit var calls: CallPolicy
    @Inject lateinit var perApp: PerAppSettingStore
    @Inject lateinit var perf: PerfTuner
    @Inject lateinit var touch: GameTouchModeManager
    @Inject lateinit var network: NetworkTelemetry
    @Inject lateinit var fps: FpsInteractor
    @Inject lateinit var charging: ChargingProfileRepository
    @Inject lateinit var stats: FpsStatsCollector
    @Inject lateinit var statsRepo: FpsStatsRepository
    @Inject lateinit var io: SettingsIO

    private val binder = Binder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wifiLock by lazy { WifiLowLatencyLock(this) }
    private var bound: Uri? = null
    private var statsJob: Job? = null

    private val screenOn = object : BroadcastReceiver() {
        // The panel drops back to its base report rate across suspend.
        override fun onReceive(c: Context, i: Intent) = touch.onScreenOn()
    }

    override fun onCreate() {
        super.onCreate()
        tiles.init()
        state.onGestureLock = sidebar::setGestureLock
        state.onStatsRecord = ::recordStats
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onBind(intent: Intent): IBinder {
        val uri = intent.data
        val pkg = uri?.takeIf { it.scheme == SCHEME && it.host == HOST }?.lastPathSegment
        if (pkg == null) {
            Log.w(TAG, "Bind without a session: $intent")
            return binder
        }
        bound = uri
        val mode = uri.getQueryParameter("mode")?.toIntOrNull() ?: SidebarMode.MODE_GAME
        if (session.currentPackage == pkg) changeMode(mode) else start(pkg, mode)
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        // A newer session binds before the old one is dropped; only the current unbind ends it.
        if (intent.data == bound) { bound = null; stop() }
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sidebar.onConfigurationChanged()
        danmaku.onConfigurationChanged()
        touch.onConfigurationChanged(newConfig)
    }

    private fun start(pkg: String, mode: Int) {
        if (session.isLive) stop()
        Log.i(TAG, "Start ${SidebarMode.toString(mode)} $pkg")
        session.start(pkg, mode)
        step("tweak restore") { tweaks.restore() }
        if (mode == SidebarMode.MODE_GAME) startGame(pkg)
        sidebar.start(pkg, mode)
        publish(pkg, mode)
    }

    /** Video and game sessions only touch the hardware for games. */
    private fun startGame(pkg: String) {
        step("boost") { setBoost(true) }
        step("optimize") { optimizer.optimizeGameLaunch(pkg) }
        io.setProp(PROP_SCHED_LIB, pkg)
        step("tweaks") { tweaks.apply() }
        step("per-app settings") { perApp.applySession(pkg) }
        step("clocks") { perf.applyForGame(pkg) }
        step("touch") { touch.onGameStart(pkg) }
        step("charging") { charging.applyActiveProfile(pkg) }
        step("wifi lock") { wifiLock.acquire(pkg) }
        step("network") {
            network.start(this, runCatching { packageManager.getApplicationInfo(pkg, 0).uid }.getOrDefault(0))
        }
        step("widgets") { widgets.start() }
        step("calls") { calls.start() }
        step("danmaku") { danmaku.start() }
        step("dolby") { applyDolby(pkg) }
    }

    private fun stopGame(pkg: String?) {
        step("stats") { recordStats(false) }
        step("danmaku") { danmaku.stop() }
        step("calls") { calls.stop() }
        step("widgets") { widgets.stop() }
        step("network") { network.stop() }
        step("wifi lock") { wifiLock.release() }
        step("charging") { charging.applyActiveProfile(null) }
        step("touch") { touch.onGameStop() }
        step("clocks") { perf.applyGlobal() }
        step("per-app settings") { perApp.clearSession(pkg) }
        step("tweak restore") { tweaks.restore() }
        io.setProp(PROP_SCHED_LIB, "none")
        step("boost") { setBoost(false) }
    }

    /** Runs one backend step; a failing backend must not take the session down with it. */
    private inline fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "$name failed", it) }
    }

    /** Playback started or stopped in the same app; a game never changes mode. */
    private fun changeMode(mode: Int) {
        if (session.mode == mode) return
        val pkg = session.currentPackage ?: return
        session.start(pkg, mode)
        sidebar.modeChanged(mode)
    }

    private fun stop() {
        val pkg = session.currentPackage ?: return
        Log.i(TAG, "Stop $pkg")
        sidebar.end()
        if (session.isGame) stopGame(pkg)
        fps.reset()
        session.stop()
        publish(null, SidebarMode.MODE_NONE)
    }

    private fun applyDolby(pkg: String) {
        if (!DolbyBridge.isAvailable(this)) return
        val profile = DolbyProfileClient.getProfile(this, pkg)
        if (profile >= 0) {
            DolbyBridge.setEnabled(this, true)
            DolbyBridge.setProfile(this, profile)
        }
    }

    private fun recordStats(on: Boolean) {
        val pkg = session.currentPackage
        if (on && pkg != null && !stats.isRecording.value) {
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            fps.start()
            stats.startRecording(pkg, label)
            state.statsRecording.value = true
            statsJob = scope.launch { stats.elapsedSeconds.collect { state.statsSeconds.intValue = it } }
        } else if (!on && stats.isRecording.value) {
            statsJob?.cancel()
            fps.dispose()
            state.statsRecording.value = false
            state.statsSeconds.intValue = 0
            stats.stopRecording()?.let {
                statsRepo.saveSession(it)
                Toast.makeText(this, "Session saved · ${it.durationSec}s", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** The overlay's main thread runs at display priority on the system cpuset during a game. */
    private fun setBoost(on: Boolean) {
        Process.setThreadPriority(if (on) Process.THREAD_PRIORITY_DISPLAY else Process.THREAD_PRIORITY_DEFAULT)
        Process.setThreadGroupAndCpuset(Process.myTid(), if (on) Process.THREAD_GROUP_SYSTEM else Process.THREAD_GROUP_DEFAULT)
    }

    /** Read by Laras's Pentas tile: the live app and the last game played. */
    private fun publish(live: String?, mode: Int) = runCatching {
        Settings.System.putString(contentResolver, KEY_SESSION_LIVE, live.orEmpty())
        if (live != null && mode == SidebarMode.MODE_GAME) Settings.System.putString(contentResolver, KEY_LAST_GAME, live)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOn) }
        stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Pentas"
        const val SCHEME = "pentas"
        const val HOST = "session"
        const val KEY_SESSION_LIVE = "pentas_session_live"
        const val KEY_LAST_GAME = "pentas_last_game"
        /** Read by init: the game process that gets the extra scheduler library. */
        private const val PROP_SCHED_LIB = "persist.sys.pentas.sched_lib_extra"
    }
}
