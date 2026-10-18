/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.monitor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yasvara.pentas.telemetry.TelemetrySnapshot
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import org.yasvara.pentas.utils.rememberAppIconCache

internal const val HOT_SOC = 70f
internal const val HOT_BATTERY = 42f

@Composable
internal fun WidgetFrame(widget: Widget, overlay: WidgetOverlay, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val s = overlay.settings
    val editing by overlay.editing
    val snap by overlay.bus.snapshot.collectAsState()
    val shape = RoundedCornerShape(if (widget == Widget.TEMP || widget == Widget.MINI) 12.dp else 14.dp)
    Box(
        Modifier
            .then(if (editing) Modifier.border(1.5.dp, cs.primary.copy(alpha = 0.6f), shape) else Modifier)
            .clip(shape).background(cs.surface.copy(alpha = s.opacity))
            .pointerInput(editing) {
                if (editing) detectDragGestures(onDragEnd = onDragEnd) { c, d -> c.consume(); onDrag(d.x, d.y) }
            }
    ) {
        when (widget) {
            Widget.CLASSIC -> Classic(snap, overlay)
            Widget.MINI -> Mini(snap, overlay)
            Widget.PROCESSES -> Processes(overlay)
            Widget.TEMP -> Temp(snap, s)
        }
    }
}

@Composable
private fun hot(c: Float, limit: Float) =
    if (c >= limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

@Composable
private fun KV(k: String, v: @Composable () -> Unit) {
    Row(Modifier.width(118.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = label(10.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        v()
    }
}

@Composable
private fun Classic(s: TelemetrySnapshot, o: WidgetOverlay) {
    val cs = MaterialTheme.colorScheme
    val set = o.settings
    val w = Widget.CLASSIC
    val fps by o.fps.realFpsHistory.collectAsState()
    val v = label(10.5.sp, 550)
    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (set.shows(w, Field.FPS)) Row(verticalAlignment = Alignment.Bottom) {
            Text("${fps.lastOrNull()?.toInt() ?: 0}", style = num(18.sp, 620), color = cs.primary)
            Text(" fps", style = label(10.5.sp), color = cs.onSurfaceVariant)
        }
        val temp = set.shows(w, Field.TEMP)
        if (set.shows(w, Field.CPU)) KV("CPU") {
            Text(buildAnnotatedString {
                append("${s.cpuUsageTotal}%% · %.1fG".format(s.cpuFreqMaxKhz / 1e6f))
                if (temp) { append(" · "); withStyle(v.toSpanStyle().copy(color = hot(s.cpuTempC, HOT_SOC))) { append("%.0f°".format(s.cpuTempC)) } }
            }, style = v, color = cs.onSurface)
        }
        if (set.shows(w, Field.GPU)) KV("GPU") {
            Text(buildAnnotatedString {
                append("${s.gpuUsage}% · ${s.gpuFreqMhz}M")
                if (temp) { append(" · "); withStyle(v.toSpanStyle().copy(color = hot(s.gpuTempC, HOT_SOC))) { append("%.0f°".format(s.gpuTempC)) } }
            }, style = v, color = cs.onSurface)
        }
        if (set.shows(w, Field.RAM)) KV("RAM") {
            Text("%.1f / %.0f G".format(s.ramUsedGb, s.ramTotalGb), style = v, color = cs.onSurface)
        }
        if (set.shows(w, Field.NET)) KV("NET") {
            Text("↓ ${speed(o.network.rxSpeedKbps.value)} ↑ ${speed(o.network.txSpeedKbps.value)}", style = v, color = cs.onSurface)
        }
    }
}

private fun speed(kbps: Float) = if (kbps >= 1024) "%.1f M".format(kbps / 1024) else "${kbps.toInt()} K"

@Composable
private fun Mini(s: TelemetrySnapshot, o: WidgetOverlay) {
    val cs = MaterialTheme.colorScheme
    val set = o.settings
    val w = Widget.MINI
    val fps by o.fps.realFpsHistory.collectAsState()
    val v = num(11.sp, 600)
    @Composable fun Cell(k: String, value: String) {
        Text(buildAnnotatedString {
            withStyle(label(10.sp, 600).toSpanStyle().copy(color = cs.onSurfaceVariant)) { append("$k ") }
            append(value)
        }, style = v, color = cs.onSurface)
    }
    Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (set.shows(w, Field.FPS)) Cell("FPS", "${fps.lastOrNull()?.toInt() ?: 0}")
        if (set.shows(w, Field.CPU)) Cell("CPU", "${s.cpuUsageTotal}%")
        if (set.shows(w, Field.GPU)) Cell("GPU", "${s.gpuUsage}%")
        if (set.shows(w, Field.RAM)) Cell("RAM", "%.1fG".format(s.ramUsedGb))
    }
}

@Composable
private fun Temp(s: TelemetrySnapshot, set: WidgetSettings) {
    val cs = MaterialTheme.colorScheme
    val w = Widget.TEMP
    @Composable fun Cell(k: String, c: Float, limit: Float) {
        Text(buildAnnotatedString {
            withStyle(label(10.sp, 600).toSpanStyle().copy(color = cs.onSurfaceVariant)) { append("$k ") }
            withStyle(num(11.sp, 600).toSpanStyle().copy(color = hot(c, limit))) { append("%.0f°".format(c)) }
        })
    }
    Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (set.shows(w, Field.CPU)) Cell("CPU", s.cpuTempC, HOT_SOC)
        if (set.shows(w, Field.GPU)) Cell("GPU", s.gpuTempC, HOT_SOC)
        if (set.shows(w, Field.BATTERY)) Cell("BAT", s.batteryTempC, HOT_BATTERY)
    }
}

@Composable
private fun Processes(o: WidgetOverlay) {
    val cs = MaterialTheme.colorScheme
    val procs by o.processes.collectAsState()
    val icons = rememberAppIconCache()
    val system = o.settings.shows(Widget.PROCESSES, Field.SYSTEM)
    val rows = procs.filter { system || icons.isApp(it.name) }.take(8)
    Column(Modifier.width(200.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        rows.forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val icon = icons.icon(p.name, 40)
                if (icon != null) Image(icon, null, Modifier.size(16.dp))
                else Box(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)).background(cs.surfaceContainerHighest))
                Text(icons.label(p.name), Modifier.weight(1f), style = label(11.sp), color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("%.0f%%".format(p.usagePercent), style = num(11.sp, 600),
                    color = if (p.usagePercent >= 50f) cs.error else cs.onSurfaceVariant)
            }
        }
        if (rows.isEmpty()) Text("—", style = label(11.sp), color = Color.Unspecified)
    }
}
