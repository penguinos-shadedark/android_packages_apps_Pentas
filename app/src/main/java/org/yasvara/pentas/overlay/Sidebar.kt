/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.yasvara.pentas.overlay.capture.GameScreenshotUtil
import org.yasvara.pentas.overlay.ui.Panel
import org.yasvara.pentas.overlay.ui.Rail
import org.yasvara.pentas.session.SidebarMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * The in-game sidebar's two windows: the edge handle, always there during a session, and
 * the panel window, added only while open. The panel and the rail sit on the handle's side,
 * the rail against the screen edge.
 */
@Singleton
class Sidebar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val overlay: Overlay,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val state = overlay.state
    private val settings = overlay.settings

    private val handleParams = overlayParams()
    private val panelParams = overlayParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        gravity = Gravity.CENTER,
    ).apply { flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH }

    private var handle: ComposeView? = null
    private var panel: ComposeView? = null
    private var scope: CoroutineScope? = null

    private val onLeft = mutableStateOf(false)
    private val idle = mutableStateOf(false)
    private val closing = mutableStateOf(false)
    private var mode = SidebarMode.MODE_NONE
    private var pkg: String? = null

    private val goIdle = Runnable { idle.value = true }
    private val forceRemove = Runnable { removePanel() }

    fun start(packageName: String, sessionMode: Int) {
        removePanel()
        pkg = packageName
        mode = sessionMode
        state.reset()
        onLeft.value = settings.x < 0
        handleParams.y = settings.y.coerceIn(0, screenHeight())
        dock()
        if (handle == null) handle = overlayView(context) { Handle() }.also {
            it.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
            }
        }
        handle?.let { h -> if (h.isAttachedToWindow) wm.updateViewLayout(h, handleParams) else runCatching { wm.addView(h, handleParams) } }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch { snapshotFlow { settings.stayAwakeState.value }.collect(::keepScreenOn) }
        }
        setGestureLock(mode == SidebarMode.MODE_GAME && settings.lockBottomGesture)
        scheduleIdle()
        GameScreenshotUtil.registerOverlay(hide = { setVisible(false) }, show = { setVisible(true) })
    }

    fun modeChanged(sessionMode: Int) {
        mode = sessionMode
        state.home()
        setGestureLock(mode == SidebarMode.MODE_GAME && settings.lockBottomGesture)
    }

    fun end() {
        removePanel()
        handle?.let { runCatching { wm.removeViewImmediate(it) } }
        handle = null
        scope?.cancel(); scope = null
        main.removeCallbacksAndMessages(null)
        setGestureLock(false)
        pkg = null
        mode = SidebarMode.MODE_NONE
    }

    fun onConfigurationChanged() {
        removePanel()
        handleParams.y = handleParams.y.coerceIn(0, screenHeight())
        handle?.let { runCatching { wm.updateViewLayout(it, handleParams) } }
    }

    private fun screenHeight() = wm.maximumWindowMetrics.bounds.height() - 96

    private fun dock() {
        handleParams.gravity = Gravity.TOP or if (onLeft.value) Gravity.START else Gravity.END
        handleParams.x = 0
    }

    private fun keepScreenOn(on: Boolean) {
        val f = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        handleParams.flags = if (on) handleParams.flags or f else handleParams.flags and f.inv()
        handle?.let { if (it.isAttachedToWindow) runCatching { wm.updateViewLayout(it, handleParams) } }
    }

    private fun scheduleIdle() {
        idle.value = false
        main.removeCallbacks(goIdle)
        main.postDelayed(goIdle, IDLE_MS)
    }

    /** `ax_gaming_gesture_lock` is the platform switch DisplayPolicy reads to block the home swipe. */
    fun setGestureLock(locked: Boolean) {
        state.gestureLock.value = locked
        Settings.Secure.putIntForUser(
            context.contentResolver, KEY_GESTURE_LOCK, if (locked && pkg != null) 1 else 0, UserHandle.USER_CURRENT,
        )
    }

    private fun setVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        handle?.visibility = if (visible && panel != null) View.GONE else v
        panel?.visibility = v
        overlay.widgets.setVisible(visible)
    }

    // ── handle ──────────────────────────────────────────────────────────

    @androidx.compose.runtime.Composable
    private fun Handle() {
        val cs = MaterialTheme.colorScheme
        val left by onLeft
        val idleAlpha = remember { settings.handleIdleAlphaState }
        val dim by animateFloatAsState(if (idle.value) idleAlpha.intValue / 100f else 1f, label = "idle")
        Box(
            Modifier.size(20.dp, 72.dp)
                .pointerInput(Unit) { detectTapGestures { showPanel() } }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { scheduleIdle() },
                        onDragEnd = {
                            settings.y = handleParams.y
                            scheduleIdle()
                        },
                    ) { c, d ->
                        c.consume()
                        handleParams.y = (handleParams.y + d.y.roundToInt()).coerceIn(0, screenHeight())
                        val toLeft = if (left) d.x > 40 else d.x < -40
                        if (toLeft) { onLeft.value = !left; settings.x = if (onLeft.value) -1 else 1; dock() }
                        handle?.let { runCatching { wm.updateViewLayout(it, handleParams) } }
                    }
                },
            contentAlignment = if (left) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Box(
                Modifier.padding(horizontal = 4.dp).size(5.dp, 48.dp).graphicsLayer { alpha = dim }
                    .clip(CircleShape).background(cs.primary)
            )
        }
    }

    // ── panel ───────────────────────────────────────────────────────────

    private fun showPanel() {
        if (panel != null) return
        closing.value = false
        overlay.brightness.start()
        overlay.fps.start()
        overlay.bus.start()
        overlay.media.start()
        overlay.record.refresh()
        state.home()
        val v = overlayView(context) {
            CompositionLocalProvider(LocalOverlay provides overlay) { PanelWindow() }
        }
        v.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) close(); false }
        runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            wm.addView(v, panelParams)
            panel = v
            handle?.visibility = View.GONE
        }.onFailure { releasePanelSources() }
        main.removeCallbacks(goIdle)
    }

    /** Animates the panel out; the delayed removal covers a frame clock that never ticks. */
    fun close() {
        if (panel == null) return
        closing.value = true
        main.removeCallbacks(forceRemove)
        main.postDelayed(forceRemove, CLOSE_TIMEOUT_MS)
    }

    private fun removePanel() {
        main.removeCallbacks(forceRemove)
        val v = panel ?: return
        panel = null
        runCatching { wm.removeViewImmediate(v) }
        releasePanelSources()
        state.keyboard.value = false
        state.reset()
        overlay.widgets.editing.value = false
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
        if (pkg != null) { handle?.visibility = View.VISIBLE; scheduleIdle() }
    }

    private fun releasePanelSources() {
        overlay.brightness.dispose()
        overlay.fps.dispose()
        overlay.bus.stop()
        overlay.media.stop()
    }

    private fun setFocusable(focusable: Boolean) {
        val v = panel ?: return
        val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        panelParams.flags = if (focusable) panelParams.flags and f.inv() else panelParams.flags or f
        runCatching { wm.updateViewLayout(v, panelParams) }
    }

    @androidx.compose.runtime.Composable
    private fun PanelWindow() {
        val left by onLeft
        val shut by closing
        val keyboard by state.keyboard
        LaunchedEffect(keyboard) { setFocusable(keyboard) }
        val slide = androidx.compose.runtime.remember { Animatable(1f) }
        LaunchedEffect(shut) {
            if (shut) {
                slide.animateTo(1f, spring(dampingRatio = 1f, stiffness = 1600f))
                main.post { removePanel() }
            } else slide.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 380f))
        }
        val height = (LocalConfiguration.current.screenHeightDp - 16).coerceAtMost(388).dp
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { close() } }) {
            Row(
                Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 8.dp).height(height)
                    .graphicsLayer {
                        translationX = slide.value * 120.dp.toPx() * if (left) -1 else 1
                        alpha = 1f - slide.value
                    }
                    .pointerInput(Unit) { detectTapGestures { } },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (left) { Rail(::close); Panel(mode) } else { Panel(mode); Rail(::close) }
            }
        }
    }

    companion object {
        const val KEY_GESTURE_LOCK = "ax_gaming_gesture_lock"
        private const val IDLE_MS = 3000L
        private const val CLOSE_TIMEOUT_MS = 500L
    }
}
