/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.session

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yasvara.pentas.R
import org.yasvara.pentas.data.AppSettings
import org.yasvara.pentas.overlay.overlayParams
import org.yasvara.pentas.overlay.overlayView
import org.yasvara.pentas.ui.kit.Sym
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the player sees when a call comes in mid-game. In Ring mode a slim pill of answer and
 * decline buttons docks beside the sidebar handle, so the call never takes the screen over;
 * in Answer and Decline mode a toast names the caller. [CallPolicy] still does the acting.
 */
@Singleton
class CallOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
) {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val wm = context.getSystemService(WindowManager::class.java)
    private var scope: CoroutineScope? = null
    private var lookup: Job? = null
    private var view: android.view.View? = null
    private var inCall by mutableStateOf(false)
    private var speakerOn by mutableStateOf(false)
    private var handledInSession = false
    private var prevSpeakerphone = false

    @Suppress("DEPRECATION")
    private val listener = object : PhoneStateListener(context.mainExecutor) {
        // The number only arrives through this listener, and only with READ_CALL_LOG.
        override fun onCallStateChanged(state: Int, number: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> ringing(number.orEmpty())
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    if (handledInSession || settings.callsMode == CallPolicy.MODE_ANSWER) {
                        inCall = true
                        enableSpeakerIfNoHeadset()
                        if (view == null && settings.callOverlay) show(null)
                    }
                }
                else -> dismiss()
            }
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        runCatching { telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE) }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        runCatching { telephony.listen(listener, PhoneStateListener.LISTEN_NONE) }
        dismiss()
        scope?.cancel()
        scope = null
    }

    private fun ringing(number: String) {
        val mode = settings.callsMode
        if (mode == CallPolicy.MODE_NONE && !settings.callOverlay) return
        handledInSession = true
        inCall = false
        lookup?.cancel()
        lookup = scope?.launch {
            val name = withContext(Dispatchers.IO) { contactName(number) }.ifEmpty { number }
            when (mode) {
                CallPolicy.MODE_ANSWER -> toast(R.string.call_answered, name)
                CallPolicy.MODE_REJECT -> toast(R.string.call_declined, name)
                else -> show(withContext(Dispatchers.IO) { contactPhoto(number) })
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun enableSpeakerIfNoHeadset() {
        prevSpeakerphone = audio.isSpeakerphoneOn
        val hasHeadset = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        if (!hasHeadset) {
            runCatching { audio.mode = AudioManager.MODE_IN_COMMUNICATION }
            audio.isSpeakerphoneOn = true
            speakerOn = true
        } else {
            speakerOn = prevSpeakerphone
        }
    }

    private fun toast(res: Int, name: String) =
        Toast.makeText(context, context.getString(res, name), Toast.LENGTH_SHORT).show()

    private fun show(photo: ImageBitmap?) {
        if (view != null) return
        val left = settings.x < 0
        val params = overlayParams(gravity = Gravity.TOP or if (left) Gravity.START else Gravity.END).apply {
            x = 1
            y = (settings.y - (71 * context.resources.displayMetrics.density).toInt()).coerceAtLeast(0)
        }
        val v = overlayView(context) {
            CallPill(
                photo = photo,
                inCall = inCall,
                speakerOn = speakerOn,
                onAccept = {
                    handledInSession = true
                    inCall = true
                    runCatching { telecom.acceptRingingCall() }
                    enableSpeakerIfNoHeadset()
                },
                onToggleSpeaker = {
                    speakerOn = !speakerOn
                    @Suppress("DEPRECATION") { audio.isSpeakerphoneOn = speakerOn }
                },
                onDecline = { runCatching { telecom.endCall() }; dismiss() },
                onHide = ::dismiss,
            )
        }
        view = v
        runCatching { wm.addView(v, params) }.onFailure { view = null }
    }

    @Suppress("DEPRECATION")
    private fun dismiss() {
        lookup?.cancel()
        if (handledInSession && speakerOn) {
            runCatching { audio.isSpeakerphoneOn = prevSpeakerphone }
        }
        inCall = false
        speakerOn = false
        handledInSession = false
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
    }

    private fun contactName(number: String) = query(number, ContactsContract.PhoneLookup.DISPLAY_NAME).orEmpty()

    private fun contactPhoto(number: String): ImageBitmap? {
        val uri = query(number, ContactsContract.PhoneLookup.PHOTO_URI) ?: return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }.getOrNull()
    }

    private fun query(number: String, column: String): String? {
        if (number.isBlank()) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }
}

@Composable
private fun CallPill(
    photo: ImageBitmap?,
    inCall: Boolean,
    speakerOn: Boolean,
    onAccept: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDecline: () -> Unit,
    onHide: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(CircleShape).background(cs.surfaceContainerHighest).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        photo?.let {
            Image(it, null, Modifier.size(52.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        }
        CallButton(Sym.Close, Color(0xFFEF5350), if (inCall) "End call" else "Decline", onDecline)
        if (!inCall) {
            CallButton(Sym.Check, Color(0xFF4CAF50), "Answer", onAccept)
        } else {
            CallButton(
                icon = Sym.GraphicEq,
                color = if (speakerOn) cs.primary else cs.surfaceContainerHigh,
                description = "Speaker",
                onClick = onToggleSpeaker,
            )
        }
        Icon(
            ImageVector.vectorResource(R.drawable.sym_expand_more), "Hide",
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(32.dp).clip(CircleShape).background(cs.surfaceContainerHigh)
                .clickable(onClick = onHide).padding(6.dp),
        )
    }
}

@Composable
private fun CallButton(icon: ImageVector, color: Color, description: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(52.dp).clip(CircleShape).background(color)) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}
