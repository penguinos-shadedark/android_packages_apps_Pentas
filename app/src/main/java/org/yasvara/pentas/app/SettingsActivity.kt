/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.app

import org.yasvara.pentas.ui.kit.Sym

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yasvara.pentas.charging.ChargingProfileListActivity
import org.yasvara.pentas.data.AppSettings
import org.yasvara.pentas.data.SystemSettings
import org.yasvara.pentas.data.fpsstats.FpsStatsRepository
import org.yasvara.pentas.device.DeviceSupport
import org.yasvara.pentas.settings.fpsstats.FpsStatsActivity
import org.yasvara.pentas.thermal.ThermalProfiles
import org.yasvara.pentas.thermal.custom.CustomProfileListActivity
import org.yasvara.pentas.ui.theme.PentasTheme
import org.yasvara.pentas.utils.rememberAppIconCache
import kotlin.math.roundToInt

/** Pentas settings: sidebar placement, library and performance overview, session behaviour. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PentasTheme {
                PentasSettingsScreen(onBack = { finish() })
            }
        }
    }
}

// ── Data ────────────────────────────────────────────────────────────────────────────────

private data class Library(val games: List<String>, val labels: List<String>)

private data class Overview(
    val library: Library,
    val sessions: Int,
    val lastFps: Float?,
    val thermal: String,
)

private fun loadOverview(context: Context): Overview {
    val pm = context.packageManager
    val system = SystemSettings(context)
    system.syncDetected(pm)
    val games = system.games
        .sortedBy { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrDefault(it) }
    val labels = games.map {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrDefault(it)
    }
    val sessions = runCatching { FpsStatsRepository(context).listSessions() }.getOrDefault(emptyList())
    val cr = context.contentResolver
    val thermal = ThermalProfiles.nameOf(
        Settings.System.getInt(cr, ThermalProfiles.KEY_THERMAL_PROFILE, 0),
        Settings.System.getString(cr, ThermalProfiles.KEY_CUSTOM_PROFILES),
    )
    return Overview(
        library = Library(games, labels),
        sessions = sessions.size,
        lastFps = sessions.maxByOrNull { it.startTimeMs }?.fpsAvg,
        thermal = thermal,
    )
}

// ── Screen ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun PentasSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appSettings = remember { AppSettings(context.applicationContext) }
    val obs = appSettings.observable
    val overview by produceState<Overview?>(null) {
        value = withContext(Dispatchers.IO) { loadOverview(context.applicationContext) }
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = top + 8.dp, bottom = bottom + 32.dp, start = 12.dp, end = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Sym.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.height(12.dp))
            Hero(appSettings)
        }

        item { SectionLabel("Library") }
        item { LibraryCard(overview?.library) { context.startActivity(Intent(context, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) } }

        item { SectionLabel("Performance") }
        item {
            SessionsCard(overview) { context.startActivity(Intent(context, FpsStatsActivity::class.java)) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThermalCard(overview?.thermal, Modifier.weight(1f)) {
                    if (DeviceSupport.thermalDaemon) CustomProfileListActivity.start(context)
                    else context.startActivity(Intent(context, GlobalActivity::class.java))
                }
                if (DeviceSupport.thermalDaemon) {
                    ChargingCard(obs, Modifier.weight(1f)) { ChargingProfileListActivity.start(context) }
                }
            }
        }

        item { SectionLabel("During a session") }
        item {
            Group {
                SwitchRow(obs, "pentas_auto_dnd", false, "Do not disturb",
                    "Silence notifications until the game closes", Position.FIRST)
                SwitchRow(obs, "pentas_danmaku", true, "Banner notifications",
                    "Show new notifications as a slim banner instead of a heads-up")
                SegmentRow(obs, "pentas_calls_mode", "0", "Incoming calls",
                    listOf("0" to "Ring", "1" to "Answer", "2" to "Decline"))
                SegmentRow(obs, "pentas_ringer_mode", "3", "Ringer",
                    listOf("0" to "Silent", "1" to "Vibrate", "2" to "Sound", "3" to "Keep"))
                SwitchRow(obs, "pentas_lock_bottom_gesture", false, "Lock home swipe",
                    "Swipe up twice within 1.5 s to leave. Side back always works")
                SwitchRow(obs, "pentas_no_three_screenshot", false, "Block three-finger screenshot",
                    "Stops the gesture from firing mid-game")
                SwitchRow(obs, "pentas_stay_awake", false, "Keep screen on", null)
                SwitchRow(obs, "pentas_auto_brightness_disabled", true, "Lock brightness",
                    "Auto-brightness pauses in game", Position.LAST)
            }
        }

        item { SectionLabel("Sidebar") }
        item {
            Group {
                IdleAlphaRow(appSettings)
            }
        }

        item { SectionLabel("Game launch") }
        item {
            Group {
                SwitchRow(obs, "pentas_memory_management", false, "Free memory",
                    "Close background apps when a game starts", Position.FIRST)
                SwitchRow(obs, "pentas_cache_management", true, "Clear large caches",
                    "Wipe the game's cache once it passes 100 MB", Position.LAST)
            }
        }

        item { SectionLabel("Banners") }
        item {
            Group {
                SwitchRow(obs, "pentas_banner_auto_fetch", true, "Game banners",
                    "From Play Store when a game is added", Position.SINGLE)
            }
        }
    }
}

// ── Hero ────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Hero(appSettings: AppSettings) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(36.dp)).background(cs.surfaceContainer)
    ) {
        Box(
            Modifier.size(300.dp).offset(x = 190.dp, y = (-70).dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(cs.primaryContainer.copy(alpha = 0.55f))
        )
        Column(Modifier.padding(24.dp)) {
            Text("Pentas", fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold,
                color = cs.onSurface)
            Text("Sidebars for games and apps", fontSize = 14.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Game", cs.primaryContainer, cs.onPrimaryContainer)
                Pill("Video", cs.surfaceContainerHighest, cs.onSurface)
            }
            Spacer(Modifier.height(22.dp))
            SidebarPlacement(appSettings, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(10.dp))
            val live = remember {
                Settings.System.getString(context.contentResolver, "pentas_session_live")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { pkg -> runCatching {
                        context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(pkg, 0)).toString()
                    }.getOrNull() }
            }
            Text(if (live != null) "Session running in $live" else "No session running",
                fontSize = 13.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** A landscape screen with the pill where the sidebar really docks (side and height). */
@Composable
private fun SidebarPlacement(appSettings: AppSettings, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val bounds = remember { context.getSystemService(android.view.WindowManager::class.java)
        .maximumWindowMetrics.bounds }
    // In landscape the docking height runs along the device's short side.
    val shortSide = minOf(bounds.width(), bounds.height()).coerceAtLeast(1)
    val longSide = maxOf(bounds.width(), bounds.height()).coerceAtLeast(1)
    val fraction = (appSettings.y.toFloat() / shortSide).coerceIn(0f, 0.85f)
    val onLeft = appSettings.x < longSide / 2
    Box(
        modifier.width(300.dp).aspectRatio(2.1f).clip(RoundedCornerShape(22.dp))
            .border(3.dp, cs.surfaceContainerHighest, RoundedCornerShape(22.dp))
            .background(Color.Black)
    ) {
        Box(Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceContainerHigh))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pillH = 34.dp
            val y = (maxHeight - pillH) * fraction
            Box(
                Modifier.align(if (onLeft) Alignment.TopStart else Alignment.TopEnd)
                    .offset(x = if (onLeft) 3.dp else (-3).dp, y = y)
                    .size(8.dp, pillH).clip(RoundedCornerShape(4.dp)).background(cs.primary)
            )
        }
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color) {
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(bg)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg) }
}

// ── Bento ───────────────────────────────────────────────────────────────────────────────

/** Card with the Yasvara press: corners 28 → 16 and a slight scale, sprung back on release. */
@Composable
private fun BentoCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val radius by animateDpAsState(if (pressed) 16.dp else 28.dp,
        spring(dampingRatio = 0.6f, stiffness = 800f), label = "radius")
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f,
        spring(dampingRatio = 0.6f, stiffness = 800f), label = "scale")
    Box(
        modifier.scale(scale).clip(RoundedCornerShape(radius)).background(container)
            .clickable(interactionSource = source, indication = null, role = Role.Button,
                onClick = onClick)
            .padding(20.dp)
    ) { content() }
}

@Composable
private fun CardLabel(text: String) =
    Text(text, fontSize = 12.sp, letterSpacing = 0.3.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun LibraryCard(library: Library?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val icons = rememberAppIconCache()
    BentoCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardLabel("Games")
                Text(library?.games?.size?.toString() ?: "–", fontSize = 44.sp, lineHeight = 48.sp,
                    fontWeight = FontWeight.Medium, color = cs.onSurface)
                if (library != null && library.labels.isNotEmpty()) {
                    Text(library.labels.joinToString(", "), fontSize = 13.sp,
                        color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(12.dp))
            // One squircle per game (up to three), then the add tile.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val slots = (library?.games ?: emptyList()).take(3)
                listOf(0, 2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (i in row until row + 2) {
                            val pkg = slots.getOrNull(i)
                            when {
                                pkg != null -> {
                                    val bmp = icons.icon(pkg, 120)
                                    if (bmp != null) Image(bmp, null,
                                        Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)))
                                    else Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp))
                                        .background(cs.surfaceContainerHighest))
                                }
                                i == slots.size -> Box(
                                    Modifier.size(40.dp).clip(RoundedCornerShape(14.dp))
                                        .border(2.dp, cs.outlineVariant, RoundedCornerShape(14.dp)),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Sym.Add, "Add game", tint = cs.outline) }
                                else -> Spacer(Modifier.size(40.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionsCard(overview: Overview?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    BentoCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column {
            CardLabel("Session history")
            Spacer(Modifier.height(6.dp))
            val count = overview?.sessions ?: 0
            if (count == 0) {
                Text("No sessions recorded yet", fontSize = 17.sp, color = cs.onSurface)
                Text("Frame rate, frame time and temperature are saved after each game.",
                    fontSize = 13.sp, color = cs.onSurfaceVariant, lineHeight = 18.sp)
                Spacer(Modifier.height(14.dp))
                // Flat dashed baseline: there is no data to draw yet.
                val line = cs.outlineVariant
                Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                    drawLine(line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 18f)))
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(count.toString(), fontSize = 44.sp, lineHeight = 48.sp,
                        fontWeight = FontWeight.Medium, color = cs.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Text(if (count == 1) "session" else "sessions", fontSize = 15.sp,
                        color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                }
                overview?.lastFps?.let {
                    Text("Last game averaged ${it.roundToInt()} fps", fontSize = 13.sp,
                        color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ThermalCard(name: String?, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    BentoCard(modifier.height(188.dp), onClick = onClick) {
        Column(Modifier.fillMaxSize()) {
            CardLabel("Thermal")
            Spacer(Modifier.height(6.dp))
            Text(name ?: "–", fontSize = 17.sp, color = cs.onSurface, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            val curve = cs.primary
            val axis = cs.outlineVariant
            Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                val w = size.width
                val h = size.height
                drawLine(axis, Offset(0f, h - 2), Offset(w, h - 2), 2.dp.toPx())
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(4f, h * 0.2f)
                    lineTo(w * 0.45f, h * 0.2f)
                    cubicTo(w * 0.62f, h * 0.2f, w * 0.66f, h * 0.62f, w * 0.78f, h * 0.7f)
                    cubicTo(w * 0.86f, h * 0.76f, w * 0.92f, h * 0.78f, w - 4f, h * 0.78f)
                }
                drawPath(p, curve, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(curve, 5.dp.toPx(), Offset(w * 0.45f, h * 0.2f))
            }
        }
    }
}

@Composable
private fun ChargingCard(
    obs: org.yasvara.pentas.utils.ObservablePreferences,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bypass by remember { obs.booleanState("bypass_charge_enabled", false) }
    BentoCard(modifier.height(188.dp), onClick = onClick) {
        Column(Modifier.fillMaxSize()) {
            CardLabel("Charging")
            Spacer(Modifier.height(6.dp))
            Text(if (bypass) "Bypass on" else "Bypass off", fontSize = 17.sp, color = cs.onSurface)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Charger → SoC, and the battery dimmed when bypass skips it.
                val active = cs.primary
                val idle = cs.outlineVariant
                Canvas(Modifier.weight(1f).height(48.dp)) {
                    val h = size.height
                    val w = size.width
                    drawLine(active, Offset(0f, h / 2), Offset(w * 0.3f, h / 2), 4.dp.toPx(),
                        StrokeCap.Round)
                    drawRoundRect(active, Offset(w * 0.3f, h * 0.2f),
                        androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.6f),
                        androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                        style = Stroke(3.dp.toPx()))
                    drawLine(if (bypass) idle else active, Offset(w * 0.62f, h / 2),
                        Offset(w * 0.86f, h / 2), 4.dp.toPx(), StrokeCap.Round,
                        pathEffect = if (bypass) PathEffect.dashPathEffect(floatArrayOf(2f, 14f)) else null)
                    drawRoundRect(if (bypass) idle else active, Offset(w * 0.88f, h * 0.22f),
                        androidx.compose.ui.geometry.Size(w * 0.11f, h * 0.56f),
                        androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                }
                Switch(checked = bypass,
                    onCheckedChange = { obs.setBoolean("bypass_charge_enabled", false, it) })
            }
        }
    }
}

// ── Connected lists ─────────────────────────────────────────────────────────────────────

private enum class Position { FIRST, MIDDLE, LAST, SINGLE }

private fun Position.shape() = when (this) {
    Position.FIRST -> RoundedCornerShape(24.dp, 24.dp, 6.dp, 6.dp)
    Position.SINGLE -> RoundedCornerShape(24.dp)
    Position.LAST -> RoundedCornerShape(6.dp, 6.dp, 24.dp, 24.dp)
    Position.MIDDLE -> RoundedCornerShape(6.dp)
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 28.dp, bottom = 10.dp))

@Composable
private fun Group(content: @Composable () -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }

@Composable
private fun RowShell(
    position: Position,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val base = Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(position.shape())
        .background(MaterialTheme.colorScheme.surfaceContainer)
    Box(
        (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

@Composable
private fun Titles(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
        if (subtitle != null) {
            Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(
    obs: org.yasvara.pentas.utils.ObservablePreferences,
    key: String,
    default: Boolean,
    title: String,
    subtitle: String?,
    position: Position = Position.MIDDLE,
) {
    val checked by remember { obs.booleanState(key, default) }
    RowShell(position, onClick = { obs.setBoolean(key, default, !checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Titles(title, subtitle, Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = { obs.setBoolean(key, default, it) })
        }
    }
}

@Composable
private fun IdleAlphaRow(appSettings: AppSettings) {
    val alpha = remember { appSettings.handleIdleAlphaState }
    RowShell(Position.SINGLE) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Titles("Idle handle opacity", "How visible the edge handle stays when untouched", Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Text("${alpha.intValue}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = alpha.intValue.toFloat(),
                onValueChange = { appSettings.handleIdleAlpha = it.roundToInt() },
                valueRange = 5f..100f,
            )
        }
    }
}

@Composable
private fun SegmentRow(
    obs: org.yasvara.pentas.utils.ObservablePreferences,
    key: String,
    default: String,
    title: String,
    options: List<Pair<String, String>>,
) {
    val value by remember { obs.stringState(key, default) }
    val cs = MaterialTheme.colorScheme
    RowShell(Position.MIDDLE) {
        Column {
            Titles(title, null)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEachIndexed { i, (v, label) ->
                    val selected = v == value
                    val outer = 20.dp
                    val inner by animateDpAsState(if (selected) 20.dp else 6.dp,
                        spring(dampingRatio = 0.6f, stiffness = 800f), label = "seg")
                    val shape = RoundedCornerShape(
                        topStart = if (i == 0) outer else inner,
                        bottomStart = if (i == 0) outer else inner,
                        topEnd = if (i == options.lastIndex) outer else inner,
                        bottomEnd = if (i == options.lastIndex) outer else inner,
                    )
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(shape)
                            .background(if (selected) cs.primary else cs.surfaceContainerHighest)
                            .clickable(role = Role.RadioButton) { obs.setString(key, default, v) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            color = if (selected) cs.onPrimary else cs.onSurface, maxLines = 1)
                    }
                }
            }
        }
    }
}

