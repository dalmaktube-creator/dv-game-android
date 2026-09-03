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
private fun ControlStage(state: TunnelStatus, click: () -> Unit) {
    val connected = state is TunnelStatus.Connected || state is TunnelStatus.Reconnecting
    val connecting = state is TunnelStatus.Preparing || state is TunnelStatus.Starting
    val stopping = state is TunnelStatus.Stopping
    val idle = !connected && !connecting && !stopping

    val inf = rememberInfiniteTransition(label = "ctrl")
    val breathe by inf.animateFloat(
        0.96f, 1.05f,
        infiniteRepeatable(tween(4400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )
    val wavePhase by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (connected) 3600 else 1560, easing = LinearEasing)),
        label = "wavePhase"
    )
    val powerPulse by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1080, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "powerPulse"
    )
    val idleToneProgress by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(4800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "idleTone"
    )
    val idleTone = lerp(Color(0xFF999895), Color(0xFFBAB9B5), idleToneProgress)
    val connectToneProgress by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1080, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "connectTone"
    )
    val connectTone = lerp(Color(0xFFC8C3B0), Color(0xFFF1CC3B), connectToneProgress)
    val glowPulse by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(4400, easing = FastOutSlowInEasing, delayMillis = 780), RepeatMode.Reverse),
        label = "glowPulse"
    )

    Box(
        Modifier.fillMaxWidth().height(335.dp),
        Alignment.Center
    ) {
        Canvas(Modifier.size(228.dp)) {
            val cx = center.x
            val cy = center.y
            val trackR = 104.5.dp.toPx()
            val fieldR = 92.5.dp.toPx()
            val tickCount = 140
            val innerRatio = 0.505f
            val outerRatio = 0.70f
            val tickWidth = 1.dp.toPx()

            fun drawTicks(r: Float, scale: Float, alpha: Float, color: Color) {
                val sr = r * scale
                val ir = sr * innerRatio
                val or = sr * outerRatio
                repeat(tickCount) { i ->
                    val a = (2.0 * PI * i / tickCount - PI / 2).toFloat()
                    val x = cos(a)
                    val y = sin(a)
                    drawLine(
                        color.copy(alpha = alpha),
                        Offset(cx + x * ir, cy + y * ir),
                        Offset(cx + x * or, cy + y * or),
                        tickWidth, StrokeCap.Round
                    )
                }
            }

            if (idle) {
                drawTicks(trackR, breathe, 0.52f, Color(0xFF9CAEA3))
            }

            if (connecting) {
                repeat(3) { k ->
                    val p = (wavePhase + k / 3f) % 1f
                    val scale = 1.01f + 0.38f * p
                    val alpha = when {
                        p < 0.13f -> (p / 0.13f) * 0.48f
                        p < 0.72f -> (0.48f - (p - 0.13f) / 0.59f * 0.32f)
                        else -> (0.16f - (p - 0.72f) / 0.28f * 0.16f)
                    }
                    drawTicks(fieldR, scale, alpha, Color(0xFF9CAEA3))
                }
            }

            if (connected) {
                repeat(3) { k ->
                    val p = (wavePhase + k / 3f) % 1f
                    val scale = 1.01f + 0.38f * p
                    val alpha = when {
                        p < 0.13f -> (p / 0.13f) * 0.28f
                        p < 0.72f -> (0.28f - (p - 0.13f) / 0.59f * 0.15f)
                        else -> (0.13f - (p - 0.72f) / 0.28f * 0.13f)
                    }
                    drawTicks(fieldR, scale, alpha, Color(0xFF4EB712))
                }
            }

            if (stopping) {
                repeat(3) { k ->
                    val p = (wavePhase + k / 3f) % 1f
                    val scale = 1.01f + 0.38f * p
                    val alpha = (1f - p) * 0.14f
                    drawTicks(fieldR, scale, alpha, Color(0xFF4EB712))
                }
            }

            val btnR = 92.5.dp.toPx()
            val gradColors = listOf(
                Color(0xFF1A1B20), Color(0xFF191A1F),
                Color(0xFF18191D), Color(0xFF17181C),
                Color(0xFF16171B), Color(0xFF15161A),
                Color(0xFF141519), Color(0xFF131418),
                Color(0xFF121318)
            )
            drawCircle(
                Brush.linearGradient(colors = gradColors, start = Offset(cx - btnR, cy - btnR), end = Offset(cx + btnR, cy + btnR)),
                btnR, Offset(cx, cy)
            )
            drawCircle(Color.White.copy(alpha = 0.0825f), btnR - 0.5.dp.toPx(), Offset(cx, cy - 0.5.dp.toPx()), style = Stroke(1.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.13f), btnR, Offset(cx, cy + 6.dp.toPx()), style = Stroke(12.dp.toPx()))

            val borderColor = if (connected) Color(0xFF4EB712) else Color.White.copy(alpha = 0.10f)
            drawCircle(borderColor, btnR, Offset(cx, cy), style = Stroke(1.dp.toPx()))
            if (connected) drawCircle(Color(0xFF4EB712).copy(alpha = 0.12f), btnR + 5.dp.toPx(), Offset(cx, cy), style = Stroke(10.dp.toPx()))

            val iconScale = 74.dp.toPx() / 24f
            val iconCx = cx
            val iconCy = cy
            val sw = 1.45f * iconScale
            val cap = StrokeCap.Round
            val iconColor = when {
                connected -> Color(0xFF4EB712)
                connecting -> connectTone
                stopping -> Color(0xFF4EB712).copy(alpha = 0.6f)
                else -> idleTone
            }
            if (connected) {
                val glowAlpha = 0.40f + 0.08f * glowPulse
                drawArc(
                    iconColor.copy(alpha = glowAlpha), 315f, 270f, false,
                    Offset(iconCx - 8f * iconScale, iconCy - 8f * iconScale),
                    Size(16f * iconScale, 16f * iconScale),
                    style = Stroke(sw * 3, cap = cap)
                )
                drawLine(
                    iconColor.copy(alpha = glowAlpha),
                    Offset(iconCx, iconCy - 9f * iconScale),
                    Offset(iconCx, iconCy - 1f * iconScale),
                    sw * 3, cap
                )
            }
            drawArc(
                iconColor, 315f, 270f, false,
                Offset(iconCx - 8f * iconScale, iconCy - 8f * iconScale),
                Size(16f * iconScale, 16f * iconScale),
                style = Stroke(sw, cap = cap)
            )
            drawLine(
                iconColor,
                Offset(iconCx, iconCy - 9f * iconScale),
                Offset(iconCx, iconCy - 1f * iconScale),
                sw, cap
            )
        }
        Box(
            Modifier.size(185.dp).clip(CircleShape)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = click),
            Alignment.Center
        )
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
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(10.dp, 15.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !locked, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .background(if (isGame) Color(0xFF1A1920) else Color(0xFF191A1E))
                .border(1.dp, Color(0x0DFFFFFF), RoundedCornerShape(12.dp)),
            Alignment.Center
        ) {
            Canvas(Modifier.size(22.dp)) {
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
            Text(title, color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.W640))
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
                Text("Auto-launch game", color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.W640))
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
                Text("Check for updates", color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.W640))
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
            Text("Reset app data", color = Color(0xFFE07070), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.W640))
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
                Text("DV Game", color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.W640))
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
                Text("Contact support", color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.W640))
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
                Text("Send feedback", color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.W640))
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
                Text("Plan", color = DvColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.W640))
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
