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
import androidx.compose.foundation.Image
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.dvgame.app.model.AppScreen
import com.dvgame.app.model.ConnectionChoice
import com.dvgame.app.model.InstalledGame
import com.dvgame.app.model.ServerProfile
import com.dvgame.app.model.TunnelStatus
import com.dvgame.app.ui.DvColors
import com.dvgame.app.ui.DvTheme
import com.dvgame.app.update.UpdateManifest
import com.dvgame.app.update.UpdateService
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

class Alpha12Activity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[MainViewModel::class.java] }
    private var afterVpnPermission: (() -> Unit)? = null
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = afterVpnPermission
        afterVpnPermission = null
        if (result.resultCode == Activity.RESULT_OK) action?.invoke()
        else model.report("مجوز VPN داده نشد؛ برای اتصال دوباره تلاش کنید")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DvTheme {
                val ui by model.ui.collectAsState()
                val status by model.tunnelStatus.collectAsState()
                Alpha12App(ui, status, model, ::startSelected, ::installUpdate)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        model.onForeground()
    }

    private fun startSelected() {
        val choice = model.connectionChoice() ?: return
        val action: () -> Unit = { connect(choice); Unit }
        val permission = VpnService.prepare(this)
        if (permission == null) action() else {
            afterVpnPermission = action
            vpnPermission.launch(permission)
        }
    }

    private fun connect(choice: ConnectionChoice) = lifecycleScope.launch {
        runCatching { model.connect(choice) }
            .onSuccess {
                if (choice.autoLaunch) {
                    val launch = packageManager.getLaunchIntentForPackage(choice.game.packageName)
                    if (launch == null) model.report("اتصال برقرار است، اما بازی باز نشد")
                    else startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            .onFailure { model.report(it.message ?: "اتصال ناموفق بود") }
    }

    private fun installUpdate(manifest: UpdateManifest) {
        if (!model.canInstallUpdates()) {
            model.report("برای نصب، اجازه نصب از این منبع را در تنظیمات اندروید فعال کنید")
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        lifecycleScope.launch {
            runCatching { model.downloadUpdate(manifest) }
                .onSuccess { file -> startActivity(UpdateService(this@Alpha12Activity).installIntent(file)) }
                .onFailure { model.report(it.message ?: "نصب به‌روزرسانی ناموفق بود") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Alpha12App(ui: AppUiState, status: TunnelStatus, model: MainViewModel, connect: () -> Unit, install: (UpdateManifest) -> Unit) {
    val subscriptionReady = ui.subscription?.let { subscription ->
        subscription.account.connectionBlockReason(subscription.serverTimeMs) == null
    } == true
    ScreenBackground {
        if (!subscriptionReady) {
            SubscriptionOnboarding(ui, model)
            return@ScreenBackground
        }
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("DV Game", fontWeight = FontWeight.ExtraBold)
                            Text(ui.message, style = MaterialTheme.typography.labelSmall, color = DvColors.Muted)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF120D26)) {
                    NavigationBarItem(ui.screen == AppScreen.HOME, { model.setScreen(AppScreen.HOME) }, { Text("خانه") }, colors = navItemColors())
                    NavigationBarItem(ui.screen == AppScreen.ACCOUNT, { model.setScreen(AppScreen.ACCOUNT) }, { Text("حساب") }, colors = navItemColors())
                    NavigationBarItem(ui.screen == AppScreen.SETTINGS, { model.setScreen(AppScreen.SETTINGS) }, { Text("تنظیمات") }, colors = navItemColors())
                }
            },
        ) { padding ->
            when (ui.screen) {
                AppScreen.HOME -> Home(ui, status, model, connect, padding)
                AppScreen.ACCOUNT -> Account(ui, padding)
                AppScreen.SETTINGS -> Settings(ui, model, install, padding)
            }
        }
    }
}

@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = DvColors.Primary,
    unselectedIconColor = DvColors.Muted,
    indicatorColor = DvColors.PrimaryDark.copy(alpha = 0.65f),
)

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = DvColors.PrimaryDeep,
    uncheckedThumbColor = DvColors.Muted,
    uncheckedTrackColor = DvColors.SurfaceHigh,
)

@Composable
private fun ScreenBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(DvColors.ScreenBackground)) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(DvColors.Primary.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 0.16f),
                    radius = size.width * 0.95f,
                ),
                center = Offset(size.width / 2f, size.height * 0.16f),
                radius = size.width * 0.95f,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(DvColors.AccentPink.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(size.width * 0.85f, size.height * 0.88f),
                    radius = size.width * 0.85f,
                ),
                center = Offset(size.width * 0.85f, size.height * 0.88f),
                radius = size.width * 0.85f,
            )
        }
        content()
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = DvColors.Surface.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, DvColors.CardBorder),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun GradientButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val brush = if (enabled) DvColors.ActiveButton
    else Brush.linearGradient(listOf(DvColors.SurfaceHigh, DvColors.SurfaceHigh))
    Box(
        modifier
            .clip(shape)
            .background(brush)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun StatusPill(text: String, ok: Boolean) {
    val color = if (ok) DvColors.Success else DvColors.Danger
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SubscriptionOnboarding(ui: AppUiState, model: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        item { AtomMark(Modifier.size(96.dp)) }
        item { Spacer(Modifier.height(18.dp)) }
        item { Text(
            "راه‌اندازی DV Game",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        ) }
        item { Spacer(Modifier.height(8.dp)) }
        item { Text(
            "برای ورود، لینک HTTPS اشتراک را وارد و تأیید کنید. لینک روی همین دستگاه ذخیره می‌شود و در اجراهای بعد دوباره پرسیده نخواهد شد.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = DvColors.Muted,
        ) }
        item { Spacer(Modifier.height(24.dp)) }
        item {
            GlassCard {
                OutlinedTextField(
                    value = ui.link,
                    onValueChange = model::setLink,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("لینک HTTPS اشتراک") },
                    placeholder = { Text("https://…") },
                    supportingText = { Text("لینک باید معتبر و اشتراک فعال باشد.") },
                    singleLine = true,
                    enabled = !ui.loading,
                    shape = RoundedCornerShape(14.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { clipboard.getText()?.text?.let(model::setLink) },
                        enabled = !ui.loading,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, DvColors.Primary),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DvColors.Primary),
                    ) { Text("چسباندن لینک") }
                    GradientButton(
                        onClick = { model.refresh() },
                        enabled = ui.link.isNotBlank() && !ui.loading,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (ui.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                        else Text("تأیید و ورود", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
        item { Text(
            ui.message,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = if (ui.subscription == null && !ui.loading && ui.link.isNotBlank())
                MaterialTheme.colorScheme.error else DvColors.Muted,
        ) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(ui: AppUiState, status: TunnelStatus, model: MainViewModel, connect: () -> Unit, padding: PaddingValues) {
    val locked = status !is TunnelStatus.Idle
    val connected = status is TunnelStatus.Connected || status is TunnelStatus.Reconnecting
    val busy = ui.loading || status is TunnelStatus.Preparing || status is TunnelStatus.Starting || status is TunnelStatus.Stopping
    val stateColor = when {
        connected -> DvColors.Success
        busy -> DvColors.Amber
        else -> DvColors.Text
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AtomConnectButton(
                    connected = connected,
                    busy = busy,
                    enabled = !ui.loading && (connected || !locked),
                    onClick = { if (connected) model.disconnect() else connect() },
                )
                Spacer(Modifier.height(6.dp))
                Text(statusTitle(status), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = stateColor)
                Text(
                    if (locked) "انتخاب بازی و سرور تا پایان اتصال قفل است" else ui.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = DvColors.Muted,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            GlassCard {
                Text("مسیر اتصال", fontWeight = FontWeight.Bold)
                ServerPicker(ui.subscription?.profiles.orEmpty(), ui.selectedServerId, !locked, model::selectServer)
                GamePicker(ui.installedGames, ui.selectedGamePackage, !locked, model::selectGame)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("اجرای خودکار بازی", fontWeight = FontWeight.SemiBold)
                        Text("بازی بلافاصله بعد از اتصال باز می‌شود", style = MaterialTheme.typography.labelSmall, color = DvColors.Muted)
                    }
                    Switch(ui.autoLaunch, model::setAutoLaunch, colors = switchColors())
                }
            }
        }
        item { SubscriptionCard(ui) }
        if (ui.fromCache) item { Text("اطلاعات ذخیره‌شده نمایش داده می‌شود و در پس‌زمینه به‌روز خواهد شد.", style = MaterialTheme.typography.bodySmall, color = DvColors.Muted) }
    }
}

@Composable
private fun SubscriptionCard(ui: AppUiState) {
    val account = ui.subscription?.account
    GlassCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("وضعیت اشتراک", fontWeight = FontWeight.Bold)
            if (account != null) {
                val active = account.state.equals("active", true)
                StatusPill(if (active) "فعال" else account.state, active)
            }
        }
        if (account == null) Text("لینک اشتراک را در تنظیمات وارد کنید", style = MaterialTheme.typography.bodySmall, color = DvColors.Muted)
        else {
            InfoRow("مصرف", "${formatBytes(account.usedBytes)} از ${formatBytes(account.totalBytes)}")
            val progress = if (account.totalBytes > 0) (account.usedBytes.toFloat() / account.totalBytes).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                { progress },
                Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = DvColors.Primary,
                trackColor = DvColors.Divider,
            )
            InfoRow("انقضا", account.expiryMs?.let(::formatDate) ?: "بدون تاریخ")
        }
    }
}

@Composable
private fun AtomConnectButton(connected: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "atom")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (connected) 5200 else 11000, easing = LinearEasing)),
        label = "spin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val accent = when {
        connected -> DvColors.Success
        busy -> DvColors.Amber
        else -> DvColors.Primary
    }
    val accentDeep = when {
        connected -> Color(0xFF065F46)
        busy -> Color(0xFF92400E)
        else -> DvColors.PrimaryDark
    }
    val accentLight = when {
        connected -> Color(0xFFA7F3D0)
        busy -> Color(0xFFFDE68A)
        else -> DvColors.PrimaryLight
    }
    Box(
        Modifier.size(260.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = if (enabled) 0.30f else 0.14f), Color.Transparent),
                    center = center,
                    radius = radius * pulse,
                ),
                center = center,
                radius = radius * pulse,
            )
            val rx = radius * 0.88f
            val ry = radius * 0.34f
            floatArrayOf(-24f, 36f, 96f).forEachIndexed { index, tilt ->
                val alphaScale = if (enabled) 1f else 0.5f
                rotate(degrees = tilt, pivot = center) {
                    drawOval(
                        color = accent.copy(alpha = (0.60f - index * 0.12f) * alphaScale),
                        topLeft = Offset(center.x - rx, center.y - ry),
                        size = Size(rx * 2f, ry * 2f),
                        style = Stroke(width = radius * 0.02f),
                    )
                }
                val direction = if (index % 2 == 0) 1.0 else -1.0
                val phase = Math.toRadians(((spin * direction + index * 120.0) % 360.0))
                val tiltRad = Math.toRadians(tilt.toDouble())
                val localX = cos(phase) * rx
                val localY = sin(phase) * ry
                val electronX = center.x + (localX * cos(tiltRad) - localY * sin(tiltRad)).toFloat()
                val electronY = center.y + (localX * sin(tiltRad) + localY * cos(tiltRad)).toFloat()
                drawCircle(accent.copy(alpha = 0.30f * alphaScale), radius * 0.085f, Offset(electronX, electronY))
                drawCircle(accentLight.copy(alpha = alphaScale), radius * 0.038f, Offset(electronX, electronY))
            }
            val coreRadius = radius * 0.40f * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accentLight, accent, accentDeep),
                    center = Offset(center.x - coreRadius * 0.38f, center.y - coreRadius * 0.42f),
                    radius = coreRadius * 1.35f,
                ),
                center = center,
                radius = coreRadius,
            )
            drawCircle(
                color = accent.copy(alpha = 0.5f),
                center = center,
                radius = coreRadius,
                style = Stroke(width = radius * 0.012f),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.7f),
                center = Offset(center.x - coreRadius * 0.34f, center.y - coreRadius * 0.40f),
                radius = coreRadius * 0.11f,
            )
        }
        if (busy) CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 3.dp, color = Color.White)
        else Text(
            if (connected) "قطع اتصال" else "شروع اتصال",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AtomMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(DvColors.Primary.copy(alpha = 0.35f), Color.Transparent),
                center = center,
                radius = radius,
            ),
            center = center,
            radius = radius,
        )
        val rx = radius * 0.86f
        val ry = radius * 0.34f
        floatArrayOf(-24f, 36f, 96f).forEachIndexed { index, tilt ->
            rotate(degrees = tilt, pivot = center) {
                drawOval(
                    color = DvColors.Primary.copy(alpha = 0.65f - index * 0.12f),
                    topLeft = Offset(center.x - rx, center.y - ry),
                    size = Size(rx * 2f, ry * 2f),
                    style = Stroke(width = radius * 0.045f),
                )
            }
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(DvColors.PrimaryLight, DvColors.Primary, DvColors.PrimaryDark),
                center = Offset(center.x - radius * 0.12f, center.y - radius * 0.14f),
                radius = radius * 0.55f,
            ),
            center = center,
            radius = radius * 0.34f,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GamePicker(games: List<InstalledGame>, selected: String?, enabled: Boolean, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = games.firstOrNull { it.packageName == selected }
    ExposedDropdownMenuBox(expanded, { expanded = it && enabled && games.isNotEmpty() }) {
        OutlinedTextField(
            value = current?.name ?: "بازی نصب‌شده‌ای انتخاب نشده",
            onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text("بازی") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            shape = RoundedCornerShape(14.dp),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            games.forEach { game -> DropdownMenuItem(
                text = { Row(verticalAlignment = Alignment.CenterVertically) { GameIcon(game); Spacer(Modifier.size(10.dp)); Column { Text(game.name); Text(game.packageName, style = MaterialTheme.typography.labelSmall, color = DvColors.Muted) } } },
                onClick = { choose(game.packageName); expanded = false },
            ) }
        }
    }
}

@Composable
private fun GameIcon(game: InstalledGame) {
    val context = LocalContext.current
    val bitmap = remember(game.packageName) { runCatching { context.packageManager.getApplicationIcon(game.packageName).toBitmap(72, 72).asImageBitmap() }.getOrNull() }
    if (bitmap != null) Image(bitmap, game.name, Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerPicker(profiles: List<ServerProfile>, selected: String?, enabled: Boolean, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = profiles.firstOrNull { it.id == selected }
    ExposedDropdownMenuBox(expanded, { expanded = it && enabled && profiles.isNotEmpty() }) {
        OutlinedTextField(
            value = current?.let { it.location.ifBlank { it.name } } ?: "سروری انتخاب نشده",
            onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text("سرور") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            shape = RoundedCornerShape(14.dp),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            profiles.forEach { profile -> DropdownMenuItem(
                text = { Column { Text(profile.name); Text(profile.location, style = MaterialTheme.typography.labelSmall, color = DvColors.Muted) } },
                onClick = { choose(profile.id); expanded = false },
            ) }
        }
    }
}

@Composable
private fun Account(ui: AppUiState, padding: PaddingValues) {
    val account = ui.subscription?.account
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("حساب کاربری", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold) }
        if (account == null) item { GlassCard { Text("برای نمایش حساب، ابتدا لینک اشتراک را وارد کنید.", color = DvColors.Muted) } }
        else {
            item {
                GlassCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(account.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("اشتراک DV Game", style = MaterialTheme.typography.labelSmall, color = DvColors.Muted)
                        }
                        val active = account.state.equals("active", true)
                        StatusPill(if (active) "فعال" else account.state, active)
                    }
                }
            }
            item {
                GlassCard {
                    val daysLeft = account.expiryMs?.let { ((it - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0) }
                    if (daysLeft != null) {
                        Text("$daysLeft", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.ExtraBold, color = DvColors.Primary)
                        Text("روز باقی‌مانده تا انقضا", style = MaterialTheme.typography.bodySmall, color = DvColors.Muted)
                    }
                    InfoRow("انقضا", account.expiryMs?.let(::formatDate) ?: "بدون تاریخ")
                }
            }
            item {
                GlassCard {
                    Text("مصرف", fontWeight = FontWeight.Bold)
                    val progress = if (account.totalBytes > 0) (account.usedBytes.toFloat() / account.totalBytes).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        { progress },
                        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = DvColors.Primary,
                        trackColor = DvColors.Divider,
                    )
                    InfoRow("استفاده‌شده", "${formatBytes(account.usedBytes)} از ${formatBytes(account.totalBytes)}")
                }
            }
        }
    }
}

@Composable
private fun Settings(ui: AppUiState, model: MainViewModel, install: (UpdateManifest) -> Unit, padding: PaddingValues) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("تنظیمات", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold) }
        item {
            GlassCard {
                Text("اشتراک", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    ui.link,
                    model::setLink,
                    Modifier.fillMaxWidth(),
                    label = { Text("لینک HTTPS اشتراک") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                GradientButton(onClick = { model.refresh() }, enabled = ui.link.isNotBlank() && !ui.loading, modifier = Modifier.fillMaxWidth()) {
                    Text("دریافت و بررسی لینک", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
        item {
            GlassCard {
                Text("به‌روزرسانی", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    ui.mirrorUrl,
                    model::setMirrorUrl,
                    Modifier.fillMaxWidth(),
                    label = { Text("نشانی جایگزین به‌روزرسانی (اختیاری)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                GradientButton(onClick = { model.checkForUpdate() }, enabled = true, modifier = Modifier.fillMaxWidth()) {
                    Text("بررسی به‌روزرسانی", fontWeight = FontWeight.Bold, color = Color.White)
                }
                ui.availableUpdate?.let { manifest ->
                    GradientButton(onClick = { install(manifest) }, enabled = true, modifier = Modifier.fillMaxWidth()) {
                        Text("دانلود و نصب نسخه ${manifest.versionName}", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                if (ui.updateStatus.isNotBlank()) Text(ui.updateStatus, style = MaterialTheme.typography.bodySmall, color = DvColors.Muted)
                OutlinedButton(
                    onClick = {
                        val target = ui.mirrorUrl.trim()
                        if (UpdateManifest.isHttps(target)) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                        else model.report("نشانی جایگزین ثبت نشده است؛ یک نشانی HTTPS معتبر وارد کنید")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, DvColors.Primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DvColors.Primary),
                ) { Text("باز کردن نشانی جایگزین") }
                Text(
                    "منبع اصلی به‌روزرسانی انتشار عمومی گیت‌هاب است؛ نشانی جایگزین کاملاً جدا از سرور گیمینگ میزبانی می‌شود و سرور گیمینگ هرگز فایل نصبی ارائه نمی‌کند.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DvColors.Muted,
                )
            }
        }
        item {
            GlassCard {
                Text("بازنشانی", fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = model::reset,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, DvColors.Danger),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DvColors.Danger),
                ) { Text("پاک‌کردن کامل اطلاعات") }
            }
        }
        item { Text("نسخه ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, color = DvColors.Muted, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun InfoRow(title: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(title, style = MaterialTheme.typography.bodyMedium, color = DvColors.Muted)
    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
}

private fun statusTitle(status: TunnelStatus): String = when (status) {
    TunnelStatus.Idle -> "آماده اتصال"
    TunnelStatus.Preparing -> "در حال آماده‌سازی"
    TunnelStatus.Starting -> "در حال اتصال"
    is TunnelStatus.Connected -> "متصل"
    is TunnelStatus.Reconnecting -> "در حال اتصال مجدد"
    TunnelStatus.Stopping -> "در حال قطع اتصال"
    is TunnelStatus.Blocked -> "اتصال مسدود شد"
    is TunnelStatus.Failed -> "خطای اتصال"
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> "%.1f GB".format(value / (1024.0 * 1024 * 1024))
    value >= 1024L * 1024 -> "%.1f MB".format(value / (1024.0 * 1024))
    else -> "%.1f KB".format(value / 1024.0)
}

private fun formatDate(value: Long): String = DateTimeFormatter.ofPattern("yyyy/MM/dd")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
