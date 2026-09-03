package com.dvgame.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.dvgame.app.model.*
import com.dvgame.app.ui.*
import com.dvgame.app.update.UpdateManifest
import com.dvgame.app.update.UpdateService
import kotlinx.coroutines.launch
import kotlin.math.*

class V59Activity : ComponentActivity() {
    private val m by lazy { ViewModelProvider(this)[MainViewModel::class.java] }
    private var afterVpn: (() -> Unit)? = null
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val action = afterVpn; afterVpn = null
        if (it.resultCode == Activity.RESULT_OK) action?.invoke()
        else m.report("مجوز VPN داده نشد")
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContent {
            DvTheme {
                val ui by m.ui.collectAsState()
                val state by m.tunnelStatus.collectAsState()
                V59App(ui, state, m, ::start, ::installUpdate)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() { super.onResume(); m.onForeground() }

    private fun start() {
        val c = m.connectionChoice() ?: return
        val action: () -> Unit = { connect(c); Unit }
        VpnService.prepare(this)?.let { afterVpn = action; vpnPermission.launch(it) } ?: action()
    }

    private fun connect(c: ConnectionChoice) = lifecycleScope.launch {
        runCatching { m.connect(c) }
            .onSuccess {
                if (c.autoLaunch) {
                    val launch = packageManager.getLaunchIntentForPackage(c.game.packageName)
                    if (launch == null) m.report("اتصال برقرار است اما بازی باز نشد")
                    else startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            .onFailure { m.report(it.message ?: "اتصال ناموفق بود") }
    }

    private fun installUpdate(manifest: UpdateManifest) {
        if (!m.canInstallUpdates()) {
            m.report("اجازه نصب از این منبع را فعال کنید")
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        lifecycleScope.launch {
            runCatching { m.downloadUpdate(manifest) }
                .onSuccess { file -> startActivity(UpdateService(this@V59Activity).installIntent(file)) }
                .onFailure { m.report(it.message ?: "نصب ناموفق بود") }
        }
    }
}

@Composable
private fun V59App(
    ui: AppUiState,
    state: TunnelStatus,
    m: MainViewModel,
    start: () -> Unit,
    install: (UpdateManifest) -> Unit,
) {
    val ready = ui.subscription?.let { it.account.connectionBlockReason(it.serverTimeMs) == null } == true
    if (!ready) {
        SubscriptionOnboarding(ui, m)
        return
    }
    var drawerOpen by remember { mutableStateOf(false) }
    var drawerPage by remember { mutableStateOf("menu") }
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerType by remember { mutableStateOf("game") }

    Box(Modifier.fillMaxSize().background(DvColors.ScreenBackground)) {
        MainScreen(ui, state, m, start, { drawerOpen = true }, { pickerType = it; pickerOpen = true })
        SettingsDrawer(drawerOpen, { drawerOpen = false; drawerPage = "menu" }, drawerPage, { drawerPage = it }, ui, m, install)
        PickerSheet(pickerOpen, { pickerOpen = false }, pickerType, ui, m)
    }
}

@Composable
private fun SubscriptionOnboarding(ui: AppUiState, m: MainViewModel) {
    Column(
        Modifier.fillMaxSize().background(DvColors.ScreenBackground).statusBarsPadding().padding(24.dp),
        Arrangement.Center, Alignment.CenterHorizontally
    ) {
        Text("راه‌اندازی DV Game", style = MaterialTheme.typography.headlineSmall, color = DvColors.Text)
        OutlinedTextField(
            ui.link, m::setLink,
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            label = { Text("لینک HTTPS اشتراک") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        Button(m::refresh, enabled = ui.link.isNotBlank(), shape = RoundedCornerShape(14.dp)) {
            Text("ادامه")
        }
        if (ui.message.isNotBlank()) Text(ui.message, Modifier.padding(top = 12.dp), color = DvColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MainScreen(
    ui: AppUiState,
    state: TunnelStatus,
    m: MainViewModel,
    start: () -> Unit,
    onMenu: () -> Unit,
    onPicker: (String) -> Unit,
) {
    val connected = state is TunnelStatus.Connected || state is TunnelStatus.Reconnecting
    val connecting = state is TunnelStatus.Preparing || state is TunnelStatus.Starting
    val stopping = state is TunnelStatus.Stopping
    val locked = connected || connecting || stopping

    Column(
        Modifier.fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = 18.dp, start = 20.dp, end = 20.dp, bottom = 10.dp)
    ) {
        MenuButton(onMenu)
        ControlStage(state, if (connected) m::disconnect else start)
        SelectionPanel(ui, locked, onPicker)
        if (ui.message.isNotBlank()) {
            Text(
                ui.message,
                Modifier.padding(top = 10.dp),
                color = DvColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MenuButton(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color(0x16FFFFFF), RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.018f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp, 14.dp)) {
            val w = size.width
            val h = size.height
            val thick = 1.5.dp.toPx()
            val col = Color(0xFFA0A3A8)
            for (i in 0..2) {
                val y = h * (0.15f + 0.35f * i)
                drawLine(col, Offset(0f, y), Offset(w, y), thick, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun rememberStateElapsedMillis(active: Boolean, exitDurationMillis: Long = 0L): Long {
    var elapsedMillis by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(active) {
        if (active) {
            val startedAt = withFrameNanos { it }
            while (true) {
                val frameTime = withFrameNanos { it }
                elapsedMillis = (frameTime - startedAt) / 1_000_000L
            }
        } else if (elapsedMillis >= 0L && exitDurationMillis > 0L) {
            val baseElapsed = elapsedMillis
            val exitStartedAt = withFrameNanos { it }
            var exitElapsed: Long
            do {
                val frameTime = withFrameNanos { it }
                exitElapsed = (frameTime - exitStartedAt) / 1_000_000L
                elapsedMillis = baseElapsed + exitElapsed
            } while (exitElapsed < exitDurationMillis)
            elapsedMillis = -1L
        } else {
            elapsedMillis = -1L
        }
    }
    return elapsedMillis
}

private fun easedPingPong(
    elapsedMillis: Long,
    durationMillis: Long,
    easing: Easing,
    delayMillis: Long = 0L,
): Float {
    if (elapsedMillis < delayMillis) return 0f
    val phase = ((elapsedMillis - delayMillis) % durationMillis).toFloat() / durationMillis
    val leg = if (phase <= 0.5f) phase * 2f else (1f - phase) * 2f
    return easing.transform(leg)
}

@Composable
private fun ControlStage(state: TunnelStatus, click: () -> Unit) {
    val connected = state is TunnelStatus.Connected || state is TunnelStatus.Reconnecting
    val connecting = state is TunnelStatus.Preparing || state is TunnelStatus.Starting
    val stopping = state is TunnelStatus.Stopping
    val idle = !connected && !connecting && !stopping

    val inf = rememberInfiniteTransition(label = "ctrl")
    val breathe by inf.animateFloat(
        0.96f, 1.05f,
        infiniteRepeatable(tween(2200, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)), RepeatMode.Reverse),
        label = "breathe"
    )
    val linkElapsedMillis = rememberStateElapsedMillis(connecting, 620L)
    val greenElapsedMillis = rememberStateElapsedMillis(connected, 620L)
    val idleElapsedMillis = rememberStateElapsedMillis(idle)
    val toneEase = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    val idleToneProgress = easedPingPong(idleElapsedMillis, 4800L, toneEase)
    val idleTone = lerp(Color(0xFF999895), Color(0xFFBAB9B5), idleToneProgress)
    val connectToneProgress = easedPingPong(linkElapsedMillis, 1080L, toneEase)
    val connectTone = lerp(Color(0xFFC8C3B0), Color(0xFFF1CC3B), connectToneProgress)
    val glowPulse = easedPingPong(greenElapsedMillis, 4400L, toneEase, 780L)
    val cssEase = CubicBezierEasing(0.25f, 0.10f, 0.25f, 1f)
    val exitEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val trackAlpha by animateFloatAsState(if (idle) 0.52f else 0f, tween(600, easing = cssEase), label = "trackAlpha")
    val linkFieldAlpha by animateFloatAsState(if (connecting) 1f else 0f, tween(if (connecting) 500 else 620, easing = if (connecting) cssEase else exitEase), label = "linkFieldAlpha")
    val greenFieldAlpha by animateFloatAsState(if (connected) 1f else 0f, tween(if (connected) 500 else 620, easing = if (connected) cssEase else exitEase), label = "greenFieldAlpha")
    val connectedToneProgress by animateFloatAsState(if (connected) 1f else 0f, tween(if (connected) 780 else 500, easing = if (connected) exitEase else cssEase), label = "connectedTone")
    val borderProgress by animateFloatAsState(if (connected) 1f else 0f, tween(1100, easing = cssEase), label = "borderProgress")

    Box(
        Modifier.fillMaxWidth().height(335.dp),
        Alignment.Center
    ) {
        Canvas(Modifier.size(348.dp)) {
            val cx = center.x
            val cy = center.y
            val trackR = 104.4.dp.toPx()
            val fieldR = 92.4.dp.toPx()
            val tickCount = 140
            val tickPeriod = 360f / tickCount
            val radialScale = sqrt(2f)
            val linkEase = CubicBezierEasing(0.18f, 0.62f, 0.34f, 1f)
            val greenEase = CubicBezierEasing(0.22f, 0.55f, 0.26f, 1f)
            val maskBands = arrayOf(
                floatArrayOf(0.505f, 0.52f, 0.0125f),
                floatArrayOf(0.52f, 0.56f, 0.0475f),
                floatArrayOf(0.56f, 0.60f, 0.115f),
                floatArrayOf(0.60f, 0.64f, 0.25f),
                floatArrayOf(0.64f, 0.672f, 0.51f),
                floatArrayOf(0.672f, 0.687f, 0.84f),
                floatArrayOf(0.687f, 0.6945f, 1f),
                floatArrayOf(0.6945f, 0.70f, 0.5f),
            )

            fun interpolate(from: Float, to: Float, amount: Float) = from + (to - from) * amount

            fun keyedOpacity(
                phase: Float,
                firstKey: Float,
                firstAlpha: Float,
                secondKey: Float,
                secondAlpha: Float,
                easing: Easing,
            ): Float = when {
                phase < firstKey -> interpolate(0f, firstAlpha, easing.transform(phase / firstKey))
                phase < secondKey -> interpolate(
                    firstAlpha,
                    secondAlpha,
                    easing.transform((phase - firstKey) / (secondKey - firstKey)),
                )
                else -> interpolate(
                    secondAlpha,
                    0f,
                    easing.transform((phase - secondKey) / (1f - secondKey)),
                )
            }

            fun drawDotField(baseRadius: Float, scale: Float, opacity: Float, color: Color) {
                val scaledRadius = baseRadius * scale
                maskBands.forEach { band ->
                    val inner = scaledRadius * band[0] * radialScale
                    val outer = scaledRadius * band[1] * radialScale
                    val radius = (inner + outer) / 2f
                    val strokeWidth = max(0.35.dp.toPx(), outer - inner)
                    val bounds = Rect(cx - radius, cy - radius, cx + radius, cy + radius)
                    val dots = Path().apply {
                        repeat(tickCount) { index ->
                            addArc(bounds, -90f + index * tickPeriod + 0.14f, 0.478625f)
                        }
                    }
                    drawPath(
                        dots,
                        color.copy(alpha = (opacity * band[2]).coerceIn(0f, 1f)),
                        style = Stroke(strokeWidth, cap = StrokeCap.Butt),
                    )
                }
            }

            if (trackAlpha > 0f) {
                drawDotField(trackR, breathe, trackAlpha * 0.58f, Color(0xFF9CAEA3))
            }

            if (linkFieldAlpha > 0f) {
                repeat(3) { index ->
                    val ageMillis = linkElapsedMillis - index * 520L
                    if (ageMillis >= 0L) {
                        val phase = (ageMillis % 1560L).toFloat() / 1560f
                        val scale = 1.01f + 0.37f * linkEase.transform(phase)
                        val opacity = keyedOpacity(phase, 0.13f, 0.48f, 0.72f, 0.16f, linkEase) * 0.58f * linkFieldAlpha
                        drawDotField(fieldR, scale, opacity, Color(0xFF9CAEA3))
                    }
                }
            }

            if (greenFieldAlpha > 0f) {
                repeat(3) { index ->
                    val ageMillis = greenElapsedMillis - index * 1200L
                    if (ageMillis >= 0L) {
                        val phase = (ageMillis % 3600L).toFloat() / 3600f
                        val scale = 1.01f + 0.37f * greenEase.transform(phase)
                        val opacity = keyedOpacity(phase, 0.15f, 0.28f, 0.74f, 0.13f, greenEase) * greenFieldAlpha
                        drawDotField(fieldR, scale, opacity, Color(0xFF4EB712))
                    }
                }
            }

            val btnR = 92.4.dp.toPx()
            val buttonCenter = Offset(cx, cy)
            val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.Black.copy(alpha = 0.40f).toArgb()
                maskFilter = android.graphics.BlurMaskFilter(
                    57.6.dp.toPx(),
                    android.graphics.BlurMaskFilter.Blur.NORMAL,
                )
            }
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawCircle(cx, cy + 24.dp.toPx(), btnR, shadowPaint)
            }
            if (borderProgress > 0f) {
                val connectedGlowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color(0xFF4EB712).copy(alpha = 0.12f * borderProgress).toArgb()
                    maskFilter = android.graphics.BlurMaskFilter(
                        12.dp.toPx(),
                        android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                }
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawCircle(cx, cy, btnR, connectedGlowPaint)
                }
            }

            val direction = Offset(0.573576f * btnR, 0.819152f * btnR)
            val gradientStart = buttonCenter - direction
            val gradientEnd = buttonCenter + direction
            val gradColors = listOf(
                Color(0xFF1A1B20), Color(0xFF191A1F),
                Color(0xFF18191D), Color(0xFF17181C),
                Color(0xFF16171B), Color(0xFF15161A),
                Color(0xFF141519), Color(0xFF131418),
                Color(0xFF121318),
            )
            drawCircle(
                Brush.linearGradient(colors = gradColors, start = gradientStart, end = gradientEnd),
                btnR,
                buttonCenter,
            )
            drawCircle(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.52f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.13f),
                    ),
                    startY = cy - btnR,
                    endY = cy + btnR,
                ),
                btnR,
                buttonCenter,
            )
            drawCircle(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.White.copy(alpha = 0.0396f),
                        0.10f to Color.White.copy(alpha = 0.03388f),
                        0.20f to Color.White.copy(alpha = 0.02816f),
                        0.31f to Color.White.copy(alpha = 0.02266f),
                        0.42f to Color.White.copy(alpha = 0.01738f),
                        0.53f to Color.White.copy(alpha = 0.01254f),
                        0.63f to Color.White.copy(alpha = 0.00836f),
                        0.72f to Color.White.copy(alpha = 0.00484f),
                        0.80f to Color.White.copy(alpha = 0.0022f),
                        0.88f to Color.Transparent,
                    ),
                    start = gradientStart,
                    end = gradientEnd,
                ),
                btnR,
                buttonCenter,
            )
            drawCircle(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0.52f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.0572f),
                    ),
                    start = gradientStart,
                    end = gradientEnd,
                ),
                btnR,
                buttonCenter,
            )
            drawCircle(
                Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.White.copy(alpha = 0.012f),
                        1f to Color.Transparent,
                    ),
                    center = Offset(cx - 26.dp.toPx(), cy - 20.dp.toPx()),
                    radius = btnR,
                ),
                btnR,
                buttonCenter,
            )
            repeat(180) { index ->
                val angle = Math.toRadians(((index * 137.508f) % 360f).toDouble())
                val distance = sqrt(((index * 73) % 181) / 181f) * btnR * 0.96f
                val point = Offset(
                    cx + cos(angle).toFloat() * distance,
                    cy + sin(angle).toFloat() * distance,
                )
                drawCircle(
                    if (index % 2 == 0) Color.White.copy(alpha = 0.004f) else Color.Black.copy(alpha = 0.003f),
                    0.35.dp.toPx(),
                    point,
                )
            }

            val borderColor = lerp(Color.White.copy(alpha = 0.10f), Color(0xFF4EB712), borderProgress)
            drawCircle(borderColor, btnR, buttonCenter, style = Stroke(1.dp.toPx()))
            drawCircle(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.White.copy(alpha = 0.0616f),
                        0.46f to Color.White.copy(alpha = 0.01078f),
                        1f to Color.Black.copy(alpha = 0.0784f),
                    ),
                    start = gradientStart,
                    end = gradientEnd,
                ),
                btnR - 0.5.dp.toPx(),
                buttonCenter,
                style = Stroke(1.dp.toPx()),
            )

            val iconScale = 74.4.dp.toPx() / 24f
            val iconCx = cx
            val iconCy = cy
            val sw = 1.45f * iconScale
            val cap = StrokeCap.Round
            val iconColor = when {
                connecting -> connectTone
                else -> lerp(idleTone, Color(0xFF4EB712), connectedToneProgress)
            }
            fun drawPowerGlyph(color: Color, strokeWidth: Float) {
                drawArc(
                    color, 315f, 270f, false,
                    Offset(iconCx - 8f * iconScale, iconCy - 8f * iconScale),
                    Size(16f * iconScale, 16f * iconScale),
                    style = Stroke(strokeWidth, cap = cap),
                )
                drawLine(
                    color,
                    Offset(iconCx, iconCy - 9f * iconScale),
                    Offset(iconCx, iconCy - 1f * iconScale),
                    strokeWidth,
                    cap,
                )
            }
            fun drawPowerGlyphGlow(color: Color, blurRadius: Float) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = sw
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    this.color = color.toArgb()
                    maskFilter = android.graphics.BlurMaskFilter(
                        blurRadius,
                        android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                }
                drawIntoCanvas { canvas ->
                    val nativeCanvas = canvas.nativeCanvas
                    nativeCanvas.drawArc(
                        android.graphics.RectF(
                            iconCx - 8f * iconScale,
                            iconCy - 8f * iconScale,
                            iconCx + 8f * iconScale,
                            iconCy + 8f * iconScale,
                        ),
                        315f, 270f, false, paint,
                    )
                    nativeCanvas.drawLine(
                        iconCx,
                        iconCy - 9f * iconScale,
                        iconCx,
                        iconCy - 1f * iconScale,
                        paint,
                    )
                }
            }
            if (connectedToneProgress > 0f) {
                val innerGlowAlpha = (0.4032f + 0.0768f * glowPulse) * connectedToneProgress
                val outerGlowAlpha = (0.08f + 0.12f * glowPulse) * connectedToneProgress
                val innerGlowBlur = (6f + 2.4f * glowPulse) * connectedToneProgress * density
                val outerGlowBlur = (12f + 7.2f * glowPulse) * connectedToneProgress * density
                drawPowerGlyphGlow(iconColor.copy(alpha = outerGlowAlpha), outerGlowBlur)
                drawPowerGlyphGlow(iconColor.copy(alpha = innerGlowAlpha), innerGlowBlur)
            }
            drawPowerGlyph(iconColor, sw)
        }
        Box(
            Modifier.size(184.8.dp).clip(CircleShape)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = click),
            Alignment.Center
        ) {}
    }
}

@Composable
private fun SelectionPanel(
    ui: AppUiState,
    locked: Boolean,
    onPicker: (String) -> Unit,
) {
    val gameName = ui.installedGames.firstOrNull { it.packageName == ui.selectedGamePackage }?.name
        ?: ui.subscription?.games?.firstOrNull { ui.installedGames.any { g -> it.packages.contains(g.packageName) } }?.name
        ?: "بازی انتخاب نشده"
    val serverName = ui.subscription?.profiles?.firstOrNull { it.id == ui.selectedServerId }?.let { it.location.ifBlank { it.name } }
        ?: "سرور انتخاب نشده"

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.025f),
        border = BorderStroke(1.dp, Color(0x16FFFFFF)),
    ) {
        Column {
            SelectorRow("GAME", gameName, true, locked) { onPicker("game") }
            HorizontalDivider(color = Color(0x16FFFFFF), thickness = 1.dp)
            SelectorRow("SERVER", serverName, false, locked) { onPicker("server") }
        }
    }
}

@Composable
private fun SelectorRow(label: String, value: String, isGame: Boolean, locked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 15.dp, vertical = 10.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !locked, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .background(if (isGame) Color(0xFF1A1920) else Color(0xFF191A1E))
                .border(1.dp, Color(0x0DFFFFFF), RoundedCornerShape(12.dp)),
            Alignment.Center
        ) {
            Canvas(Modifier.size(19.dp)) {
                val s = size.minDimension / 24f
                val col = if (isGame) Color(0xFFD4C3A0) else Color(0xFFA7A9AE)
                if (isGame) {
                    val p = Path().apply {
                        moveTo(5f * s, 17f * s)
                        lineTo(9f * s, 7f * s)
                        lineTo(12f * s, 13f * s)
                        lineTo(16f * s, 7f * s)
                        lineTo(19f * s, 17f * s)
                        lineTo(15f * s, 19f * s)
                        lineTo(9f * s, 19f * s)
                        close()
                    }
                    drawPath(p, col, style = Fill)
                } else {
                    drawRoundRect(col, Offset(4f * s, 5f * s), Size(16f * s, 5f * s), CornerRadius(1.5f * s, 1.5f * s))
                    drawRoundRect(col, Offset(4f * s, 14f * s), Size(16f * s, 5f * s), CornerRadius(1.5f * s, 1.5f * s))
                    drawCircle(col, 0.6f * s, Offset(8f * s, 7.5f * s))
                    drawCircle(col, 0.6f * s, Offset(8f * s, 16.5f * s))
                    drawLine(col, Offset(12f * s, 7.5f * s), Offset(17f * s, 7.5f * s), 1f * s, StrokeCap.Round)
                    drawLine(col, Offset(12f * s, 16.5f * s), Offset(17f * s, 16.5f * s), 1f * s, StrokeCap.Round)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = Color(0xFF676A71),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 1.4.sp,
                ),
            )
            Text(
                value,
                color = DvColors.Text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
        Canvas(Modifier.size(16.dp)) {
            val s = size.minDimension / 24f
            val col = Color(0xFF65686F)
            val sw = 1.5f * s
            drawLine(col, Offset(8f * s, 9f * s), Offset(12f * s, 13f * s), sw, StrokeCap.Round)
            drawLine(col, Offset(12f * s, 13f * s), Offset(16f * s, 9f * s), sw, StrokeCap.Round)
        }
    }
}

@Composable
private fun SettingsDrawer(
    isOpen: Boolean,
    onClose: () -> Unit,
    page: String,
    onNavigate: (String) -> Unit,
    ui: AppUiState,
    m: MainViewModel,
    install: (UpdateManifest) -> Unit,
) {
    val drawerAnim by animateFloatAsState(if (isOpen) 1f else 0f, tween(280, easing = FastOutSlowInEasing), label = "drawer")
    if (drawerAnim <= 0f && !isOpen) return
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color(0xADD00000).copy(alpha = 0.68f * drawerAnim))
                .clickable(remember { MutableInteractionSource() }, indication = null) { onClose() }
        )
        Box(
            Modifier.fillMaxHeight()
                .fillMaxWidth(0.82f)
                .align(Alignment.CenterEnd)
                .offset(x = (1f - drawerAnim).let { (it * 300).dp })
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF141519), Color(0xFF0E0F12))),
                )
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)), RectangleShape)
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (page != "menu") {
                        Box(
                            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.02f))
                                .clickable(remember { MutableInteractionSource() }, indication = null) { onNavigate("menu") },
                            Alignment.Center
                        ) {
                            Canvas(Modifier.size(16.dp)) {
                                val s = size.minDimension / 24f
                                val col = Color(0xFFA0A3A8)
                                val sw = 1.5f * s
                                drawLine(col, Offset(15f * s, 6f * s), Offset(9f * s, 12f * s), sw, StrokeCap.Round)
                                drawLine(col, Offset(9f * s, 12f * s), Offset(15f * s, 18f * s), sw, StrokeCap.Round)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (page == "menu") "Menu" else page.replaceFirstChar { it.uppercase() },
                        color = DvColors.Text,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.02f))
                            .clickable(remember { MutableInteractionSource() }, indication = null) { onClose() },
                        Alignment.Center
                    ) {
                        Canvas(Modifier.size(14.dp)) {
                            val s = size.minDimension / 24f
                            val col = Color(0xFFA0A3A8)
                            val sw = 1.5f * s
                            drawLine(col, Offset(7f * s, 7f * s), Offset(17f * s, 17f * s), sw, StrokeCap.Round)
                            drawLine(col, Offset(17f * s, 7f * s), Offset(7f * s, 17f * s), sw, StrokeCap.Round)
                        }
                    }
                }
                when (page) {
                    "menu" -> DrawerMenu(onNavigate)
                    "settings" -> SettingsDetail(ui, m, install)
                    "about" -> AboutDetail()
                    "contact" -> ContactDetail()
                    "subscription" -> SubscriptionDetail(ui)
                }
            }
        }
    }
}

@Composable
private fun DrawerMenu(onNavigate: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        DrawerMenuItem("Settings", "App preferences", 0) { onNavigate("settings") }
        DrawerMenuItem("About", "App information", 1) { onNavigate("about") }
        DrawerMenuItem("Contact", "Support and feedback", 2) { onNavigate("contact") }
        DrawerMenuItem("Subscription", "Plan and billing", 3) { onNavigate("subscription") }
    }
}

@Composable
private fun DrawerMenuItem(title: String, subtitle: String, iconType: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(15.dp))
            .background(Color.White.copy(alpha = 0.02f))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(15.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(12.dp, 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF1E2024)),
            Alignment.Center
        ) {
            Canvas(Modifier.size(18.dp)) {
                val s = size.minDimension / 24f
                val col = Color(0xFFD8C8A6)
                val sw = 1.4f * s
                val cap = StrokeCap.Round
                when (iconType) {
                    0 -> {
                        drawCircle(col, center = Offset(12f * s, 12f * s), radius = 3.5f * s, style = Stroke(sw))
                        val p = Path().apply {
                            moveTo(12f * s, 3f * s); lineTo(12f * s, 6f * s)
                            moveTo(12f * s, 21f * s); lineTo(12f * s, 18f * s)
                            moveTo(3f * s, 12f * s); lineTo(6f * s, 12f * s)
                            moveTo(21f * s, 12f * s); lineTo(18f * s, 12f * s)
                            moveTo(5.6f * s, 5.6f * s); lineTo(7.8f * s, 7.8f * s)
                            moveTo(18.4f * s, 18.4f * s); lineTo(16.2f * s, 16.2f * s)
                            moveTo(18.4f * s, 5.6f * s); lineTo(16.2f * s, 7.8f * s)
                            moveTo(5.6f * s, 18.4f * s); lineTo(7.8f * s, 16.2f * s)
                        }
                        drawPath(p, col, style = Stroke(sw, cap = cap))
                    }
                    1 -> {
                        drawCircle(col, center = Offset(12f * s, 12f * s), radius = 9f * s, style = Stroke(sw))
                        drawLine(col, Offset(12f * s, 11f * s), Offset(12f * s, 16f * s), sw, cap)
                        drawCircle(col, center = Offset(12f * s, 8f * s), radius = 0.8f * s)
                    }
                    2 -> {
                        drawRoundRect(col, Offset(3f * s, 6f * s), Size(18f * s, 12f * s), CornerRadius(2f * s, 2f * s), style = Stroke(sw))
                        val p = Path().apply {
                            moveTo(5f * s, 8f * s); lineTo(12f * s, 13f * s); lineTo(19f * s, 8f * s)
                        }
                        drawPath(p, col, style = Stroke(sw, cap = cap))
                    }
                    3 -> {
                        drawRoundRect(col, Offset(3f * s, 5f * s), Size(18f * s, 14f * s), CornerRadius(2f * s, 2f * s), style = Stroke(sw))
                        drawLine(col, Offset(3f * s, 9f * s), Offset(21f * s, 9f * s), sw, cap)
                        drawLine(col, Offset(7f * s, 15f * s), Offset(13f * s, 15f * s), sw, cap)
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
            Text(subtitle, color = Color(0xFF65686F), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
        }
    }
}

@Composable
private fun SettingsDetail(ui: AppUiState, m: MainViewModel, install: (UpdateManifest) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(15.dp))
                .background(Color.White.copy(alpha = 0.02f)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(15.dp))
                .padding(14.dp, 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Auto-launch game", color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                Text("Open the game after boosting", color = Color(0xFF65686F), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
            }
            ToggleSwitch(ui.autoLaunch) { m.setAutoLaunch(it) }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(15.dp))
                .background(Color.White.copy(alpha = 0.02f)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(15.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) { m.checkForUpdate() }
                .padding(14.dp, 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Check for updates", color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                Text(ui.updateStatus, color = Color(0xFF65686F), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
            }
            if (ui.availableUpdate != null) {
                TextButton(onClick = { ui.availableUpdate?.let { install(it) } }) { Text("Install") }
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(15.dp))
                .background(Color.White.copy(alpha = 0.02f)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(15.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) { m.reset() }
                .padding(14.dp, 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Reset app data", color = Color(0xFFE07070), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
private fun AboutDetail() {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(15.dp))
                .background(Color.White.copy(alpha = 0.02f)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(15.dp))
                .padding(14.dp, 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("DV Game", color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                Text("Connection booster for mobile games", color = Color(0xFF65686F), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(15.dp))
                .background(Color.White.copy(alpha = 0.02f)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(15.dp))
                .padding(14.dp, 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Version", color = Color(0xFF65686F), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp))
            Spacer(Modifier.weight(1f))
            Text(com.dvgame.app.BuildConfig.VERSION_NAME, color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.W600))
        }
    }
}

@Composable
private fun ContactDetail() {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(15.dp))
                .background(Color.White.copy(alpha = 0.02f)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(15.dp))
                .padding(14.dp, 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Contact support", color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                Text("Get help with connection or account issues", color = Color(0xFF65686F), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
            }
            Canvas(Modifier.size(16.dp)) {
                val s = size.minDimension / 24f
                val col = Color(0xFF65686F)
                val sw = 1.5f * s
                drawLine(col, Offset(9f * s, 6f * s), Offset(15f * s, 12f * s), sw, StrokeCap.Round)
                drawLine(col, Offset(15f * s, 12f * s), Offset(9f * s, 18f * s), sw, StrokeCap.Round)
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(15.dp))
                .background(Color.White.copy(alpha = 0.02f)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(15.dp))
                .padding(14.dp, 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Send feedback", color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                Text("Share ideas and report problems", color = Color(0xFF65686F), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
            }
            Canvas(Modifier.size(16.dp)) {
                val s = size.minDimension / 24f
                val col = Color(0xFF65686F)
                val sw = 1.5f * s
                drawLine(col, Offset(9f * s, 6f * s), Offset(15f * s, 12f * s), sw, StrokeCap.Round)
                drawLine(col, Offset(15f * s, 12f * s), Offset(9f * s, 18f * s), sw, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun SubscriptionDetail(ui: AppUiState) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        val sub = ui.subscription
        val account = sub?.account
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(15.dp))
                .background(Color.White.copy(alpha = 0.02f)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(15.dp))
                .padding(14.dp, 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Plan", color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                Text(account?.name ?: "No active plan", color = Color(0xFF65686F), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
            }
            Canvas(Modifier.size(16.dp)) {
                val s = size.minDimension / 24f
                val col = Color(0xFF65686F)
                val sw = 1.5f * s
                drawLine(col, Offset(9f * s, 6f * s), Offset(15f * s, 12f * s), sw, StrokeCap.Round)
                drawLine(col, Offset(15f * s, 12f * s), Offset(9f * s, 18f * s), sw, StrokeCap.Round)
            }
        }
        if (account != null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(15.dp))
                    .background(Color.White.copy(alpha = 0.02f)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(15.dp))
                    .padding(14.dp, 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Status", color = Color(0xFF65686F), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp))
                Spacer(Modifier.weight(1f))
                Text(account.state, color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.W600))
            }
        }
    }
}

@Composable
private fun PickerSheet(
    isOpen: Boolean,
    onClose: () -> Unit,
    type: String,
    ui: AppUiState,
    m: MainViewModel,
) {
    val sheetAnim by animateFloatAsState(if (isOpen) 1f else 0f, tween(280, easing = FastOutSlowInEasing), label = "sheet")
    if (sheetAnim <= 0f && !isOpen) return

    val title = if (type == "game") "Select game" else "Select server"
    val selectedId = if (type == "game") ui.selectedGamePackage else ui.selectedServerId

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color(0xADD00000).copy(alpha = 0.56f * sheetAnim))
                .clickable(remember { MutableInteractionSource() }, indication = null) { onClose() }
        )
        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .offset(y = ((1f - sheetAnim) * 400).dp)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF17181C), Color(0xFF101114))))
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), Alignment.Center) {
                    Box(Modifier.size(38.dp, 4.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFF37393F)))
                }
                Row(
                    Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, color = DvColors.Text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600))
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.02f))
                            .clickable(remember { MutableInteractionSource() }, indication = null) { onClose() },
                        Alignment.Center
                    ) {
                        Canvas(Modifier.size(14.dp)) {
                            val s = size.minDimension / 24f
                            val col = Color(0xFFA0A3A8)
                            val sw = 1.5f * s
                            drawLine(col, Offset(7f * s, 7f * s), Offset(17f * s, 17f * s), sw, StrokeCap.Round)
                            drawLine(col, Offset(17f * s, 7f * s), Offset(7f * s, 17f * s), sw, StrokeCap.Round)
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (type == "game") {
                        val games = ui.installedGames
                        if (games.isEmpty()) {
                            item { Text("No approved game installed", color = DvColors.Muted, style = MaterialTheme.typography.bodySmall) }
                        }
                        items(games) { game ->
                            PickerOption(game.name, game.packageName, game.packageName == selectedId) {
                                m.selectGame(game.packageName); onClose()
                            }
                        }
                    } else {
                        val servers = ui.subscription?.profiles ?: emptyList()
                        items(servers) { server ->
                            PickerOption(server.name, server.location.ifBlank { server.id }, server.id == selectedId) {
                                m.selectServer(server.id); onClose()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PickerOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0xFFD8C8A6).copy(alpha = 0.055f) else Color.White.copy(alpha = 0.02f))
            .border(1.dp, if (selected) Color(0xFFD8C8A6).copy(alpha = 0.28f) else Color(0x14FFFFFF), RoundedCornerShape(14.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(12.dp, 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E2024)),
            Alignment.Center
        ) {
            Canvas(Modifier.size(16.dp)) {
                val s = size.minDimension / 24f
                val col = Color(0xFFA7A9AE)
                drawCircle(col, center = Offset(12f * s, 12f * s), radius = 8f * s, style = Stroke(1.4f * s))
                drawLine(col, Offset(12f * s, 8f * s), Offset(12f * s, 12f * s), 1.4f * s, StrokeCap.Round)
                drawLine(col, Offset(12f * s, 12f * s), Offset(15f * s, 14f * s), 1.4f * s, StrokeCap.Round)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.W600))
            Text(subtitle, color = Color(0xFF65686F), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
        }
        if (selected) {
            Box(
                Modifier.size(18.dp).clip(CircleShape).background(Color(0xFFD8C8A6)),
                Alignment.Center
            ) {
                Canvas(Modifier.size(10.dp)) {
                    val s = size.minDimension / 24f
                    drawLine(Color(0xFF16171A), Offset(7f * s, 12f * s), Offset(10f * s, 15f * s), 2f * s, StrokeCap.Round)
                    drawLine(Color(0xFF16171A), Offset(10f * s, 15f * s), Offset(17f * s, 8f * s), 2f * s, StrokeCap.Round)
                }
            }
        } else {
            Box(Modifier.size(18.dp).clip(CircleShape).border(1.dp, Color(0x33FFFFFF), CircleShape))
        }
    }
}

@Composable
private fun ToggleSwitch(checked: Boolean, onToggle: (Boolean) -> Unit) {
    val thumbOffset by animateDpAsState(if (checked) 19.dp else 0.dp, tween(200), label = "thumb")
    Box(
        Modifier.size(44.dp, 25.dp).clip(RoundedCornerShape(999.dp))
            .background(if (checked) Color(0xFF7D735D) else Color(0xFF292B2F))
            .clickable(remember { MutableInteractionSource() }, indication = null) { onToggle(!checked) },
        Alignment.CenterStart
    ) {
        Box(Modifier.padding(start = 4.dp).offset(x = thumbOffset).size(17.dp).clip(CircleShape).background(Color(0xFFD9D8D3)))
    }
}
