/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.session.danmaku

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.TextView
import androidx.core.animation.doOnEnd
import dagger.hilt.android.qualifiers.ApplicationContext
import org.yasvara.pentas.data.AppSettings
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows notifications that arrive during a game as a short banner at the top of the screen
 * instead of a heads-up, so the game keeps its touches. Banners queue and play one at a time.
 */
@Singleton
class DanmakuOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private val density = context.resources.displayMetrics.density

    private val label = TextView(context).apply {
        gravity = Gravity.CENTER
        maxLines = 2
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setShadowLayer(4f * density, 0f, 0f, Color.BLACK)
        isFocusable = false
        isClickable = false
    }

    private val params = LayoutParams().apply {
        height = LayoutParams.WRAP_CONTENT
        flags = LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_HARDWARE_ACCELERATED
        type = LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP
    }

    private var listener: DanmakuListener? = null
    private var animator: ValueAnimator? = null
    private var hideRunnable: Runnable? = null

    /** Starts listening. Does nothing when banners are off in settings. */
    fun start() {
        if (listener != null || !settings.danmaku) return
        val l = DanmakuListener(this)
        try {
            l.registerAsSystemService(context, ComponentName(context, DanmakuListener::class.java), UserHandle.USER_CURRENT)
            listener = l
        } catch (e: RemoteException) {
            Log.e(TAG, "Could not register the notification listener", e)
        }
        updateParams()
    }

    fun stop() {
        listener?.let { runCatching { it.unregisterAsSystemService() } }
        listener = null
        animator?.cancel()
        hideRunnable?.let(handler::removeCallbacks)
        hideRunnable = null
        queue.clear()
        remove()
    }

    fun onConfigurationChanged() {
        updateParams()
        if (label.parent != null) runCatching { wm.updateViewLayout(label, params) }
    }

    fun show(text: String) = handler.post {
        if (label.parent == null) play(text) else queue.add(text)
    }

    fun labelOf(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("")

    private fun play(text: String) {
        label.text = text
        label.alpha = 0f
        runCatching { wm.addView(label, params) }.onFailure { return }
        slide(APPEAR_MS, from = rest * 0.5f, to = rest, alphaFrom = 0f, alphaTo = 1f) {
            hideRunnable = Runnable { dismiss() }.also { handler.postDelayed(it, HOLD_MS) }
        }
    }

    private fun dismiss() {
        slide(DISAPPEAR_MS, from = rest, to = rest * 1.5f, alphaFrom = 1f, alphaTo = 0f) {
            val next = queue.poll()
            if (next == null) remove() else { remove(); play(next) }
        }
    }

    private fun slide(duration: Long, from: Float, to: Float, alphaFrom: Float, alphaTo: Float, onEnd: () -> Unit) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener {
                val t = it.animatedFraction
                label.alpha = alphaFrom + (alphaTo - alphaFrom) * t
                params.y = (from + (to - from) * t).toInt()
                if (label.parent != null) runCatching { wm.updateViewLayout(label, params) }
            }
            doOnEnd { onEnd() }
            start()
        }
    }

    private val rest: Float
        get() = (if (portrait()) OFFSET_PORTRAIT_DP else OFFSET_LANDSCAPE_DP) * density

    private fun portrait() = wm.currentWindowMetrics.bounds.let { it.height() >= it.width() }

    private fun updateParams() {
        params.width = wm.currentWindowMetrics.bounds.width() * MAX_WIDTH_PERCENT / 100
        params.y = rest.toInt()
    }

    private fun remove() {
        if (label.parent != null) runCatching { wm.removeViewImmediate(label) }
    }

    private companion object {
        const val TAG = "DanmakuOverlay"
        const val APPEAR_MS = 500L
        const val HOLD_MS = 2000L
        const val DISAPPEAR_MS = 300L
        const val OFFSET_PORTRAIT_DP = 36
        const val OFFSET_LANDSCAPE_DP = 48
        const val MAX_WIDTH_PERCENT = 75
    }
}
