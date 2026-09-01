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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.dvgame.app.ui.DvTheme
import com.dvgame.app.update.UpdateManifest
import com.dvgame.app.update.UpdateService
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    if (!subscriptionReady) {
        SubscriptionOnboarding(ui, model)
        return
    }
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("DV Game", fontWeight = FontWeight.Bold); Text(ui.message, style = MaterialTheme.typography.labelSmall) } }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(ui.screen == AppScreen.HOME, { model.setScreen(AppScreen.HOME) }, { Text("خانه") })
                NavigationBarItem(ui.screen == AppScreen.ACCOUNT, { model.setScreen(AppScreen.ACCOUNT) }, { Text("حساب") })
                NavigationBarItem(ui.screen == AppScreen.SETTINGS, { model.setScreen(AppScreen.SETTINGS) }, { Text("تنظیمات") })
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

@Composable
private fun SubscriptionOnboarding(ui: AppUiState, model: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            item { Text(
                "راه‌اندازی DV Game",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ) }
            item { Spacer(Modifier.height(8.dp)) }
            item { Text(
                "برای ورود، لینک HTTPS اشتراک را وارد و تأیید کنید. لینک روی همین دستگاه ذخیره می‌شود و در اجراهای بعد دوباره پرسیده نخواهد شد.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ) }
            item { Spacer(Modifier.height(24.dp)) }
            item { OutlinedTextField(
                value = ui.link,
                onValueChange = model::setLink,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("لینک HTTPS اشتراک") },
                placeholder = { Text("https://…") },
                supportingText = { Text("لینک باید معتبر و اشتراک فعال باشد.") },
                singleLine = true,
                enabled = !ui.loading,
            ) }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { clipboard.getText()?.text?.let(model::setLink) },
                        enabled = !ui.loading,
                        modifier = Modifier.weight(1f),
                    ) { Text("چسباندن لینک") }
                    Button(
                        onClick = { model.refresh() },
                        enabled = ui.link.isNotBlank() && !ui.loading,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (ui.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text("تأیید و ورود")
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
            item { Text(
                ui.message,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = if (ui.subscription == null && !ui.loading && ui.link.isNotBlank())
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            ) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(ui: AppUiState, status: TunnelStatus, model: MainViewModel, connect: () -> Unit, padding: PaddingValues) {
    val locked = status !is TunnelStatus.Idle
    val connected = status is TunnelStatus.Connected || status is TunnelStatus.Reconnecting
    val busy = ui.loading || status is TunnelStatus.Preparing || status is TunnelStatus.Starting || status is TunnelStatus.Stopping
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AtomConnectButton(
                    connected = connected,
                    busy = busy,
                    enabled = !ui.loading && (connected || !locked),
                    onClick = { if (connected) model.disconnect() else connect() },
                )
                Spacer(Modifier.height(10.dp))
                Text(statusTitle(status), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (locked) "انتخاب بازی و سرور تا پایان اتصال قفل است" else ui.message,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item { ServerPicker(ui.subscription?.profiles.orEmpty(), ui.selectedServerId, !locked, model::selectServer) }
        item { GamePicker(ui.installedGames, ui.selectedGamePackage, !locked, model::selectGame) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("اجرای خودکار بازی", fontWeight = FontWeight.SemiBold); Text("بعد از اتصال موفق", style = MaterialTheme.typography.labelSmall) }
                Switch(ui.autoLaunch, model::setAutoLaunch)
            }
        }
        item { SubscriptionCard(ui) }
        if (ui.fromCache) item { Text("اطلاعات ذخیره‌شده نمایش داده می‌شود و در پس‌زمینه به‌روز خواهد شد.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SubscriptionCard(ui: AppUiState) {
    val account = ui.subscription?.account
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("وضعیت اشتراک", fontWeight = FontWeight.Bold)
            if (account == null) Text("لینک اشتراک را در تنظیمات وارد کنید", style = MaterialTheme.typography.bodySmall)
            else {
                InfoRow("وضعیت", if (account.state.equals("active", true)) "فعال" else account.state)
                InfoRow("مصرف", "${formatBytes(account.usedBytes)} از ${formatBytes(account.totalBytes)}")
                val progress = if (account.totalBytes > 0) (account.usedBytes.toFloat() / account.totalBytes).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
                InfoRow("انقضا", account.expiryMs?.let(::formatDate) ?: "بدون تاریخ")
            }
        }
    }
}

@Composable
private fun AtomConnectButton(connected: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "atom")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (connected) 4200 else 9000, easing = LinearEasing)),
        label = "spin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val accent = when {
        connected -> Color(0xFF25E39A)
        busy -> Color(0xFFFFC24D)
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier.size(250.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.35f), Color.Transparent),
                    center = center,
                    radius = radius * pulse,
                ),
                radius = radius * pulse,
            )
            repeat(3) { index ->
                val direction = if (index % 2 == 0) 1f else -1f
                rotate(degrees = spin * direction + index * 60f) {
                    drawOval(
                        color = accent.copy(alpha = 0.55f),
                        topLeft = Offset(center.x - radius * 0.86f, center.y - radius * 0.32f),
                        size = Size(radius * 1.72f, radius * 0.64f),
                        style = Stroke(width = radius * 0.035f),
                    )
                    drawCircle(
                        color = accent,
                        radius = radius * 0.07f,
                        center = Offset(center.x + radius * 0.86f, center.y),
                    )
                }
            }
            drawCircle(color = accent.copy(alpha = 0.18f), radius = radius * 0.42f * pulse)
            drawCircle(color = accent, radius = radius * 0.3f, style = Stroke(width = radius * 0.03f))
        }
        if (busy) CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 3.dp)
        else Text(
            if (connected) "قطع اتصال" else "شروع اتصال",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
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
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            games.forEach { game -> DropdownMenuItem(
                text = { Row(verticalAlignment = Alignment.CenterVertically) { GameIcon(game); Spacer(Modifier.size(10.dp)); Column { Text(game.name); Text(game.packageName, style = MaterialTheme.typography.labelSmall) } } },
                onClick = { choose(game.packageName); expanded = false },
            ) }
        }
    }
}

@Composable
private fun GameIcon(game: InstalledGame) {
    val context = LocalContext.current
    val bitmap = remember(game.packageName) { runCatching { context.packageManager.getApplicationIcon(game.packageName).toBitmap(72, 72).asImageBitmap() }.getOrNull() }
    if (bitmap != null) Image(bitmap, game.name, Modifier.size(36.dp))
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
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            profiles.forEach { profile -> DropdownMenuItem(
                text = { Column { Text(profile.name); Text(profile.location, style = MaterialTheme.typography.labelSmall) } },
                onClick = { choose(profile.id); expanded = false },
            ) }
        }
    }
}

@Composable
private fun Account(ui: AppUiState, padding: PaddingValues) {
    val account = ui.subscription?.account
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("حساب کاربری", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (account == null) item { Text("برای نمایش حساب، ابتدا لینک اشتراک را در تنظیمات وارد کنید.") }
        else {
            item { InfoRow("نام", account.name) }
            item { InfoRow("وضعیت", if (account.state.equals("active", true)) "فعال" else account.state) }
            item {
                val progress = if (account.totalBytes > 0) (account.usedBytes.toFloat() / account.totalBytes).coerceIn(0f, 1f) else 0f
                Column { Text("مصرف: ${formatBytes(account.usedBytes)} از ${formatBytes(account.totalBytes)}"); Spacer(Modifier.height(8.dp)); LinearProgressIndicator({ progress }, Modifier.fillMaxWidth()) }
            }
            item { InfoRow("انقضا", account.expiryMs?.let(::formatDate) ?: "بدون تاریخ") }
        }
    }
}

@Composable
private fun Settings(ui: AppUiState, model: MainViewModel, install: (UpdateManifest) -> Unit, padding: PaddingValues) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("تنظیمات", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(ui.link, model::setLink, Modifier.fillMaxWidth(), label = { Text("لینک HTTPS اشتراک") }, singleLine = true) }
        item { Button({ model.refresh() }, enabled = ui.link.isNotBlank() && !ui.loading, modifier = Modifier.fillMaxWidth()) { Text("دریافت و بررسی لینک") } }
        item { HorizontalDivider() }
        item { OutlinedTextField(ui.mirrorUrl, model::setMirrorUrl, Modifier.fillMaxWidth(), label = { Text("نشانی جایگزین به‌روزرسانی (اختیاری)") }, singleLine = true) }
        item { Button({ model.checkForUpdate() }, Modifier.fillMaxWidth()) { Text("بررسی به‌روزرسانی") } }
        ui.availableUpdate?.let { manifest -> item { Button({ install(manifest) }, Modifier.fillMaxWidth()) { Text("دانلود و نصب نسخه ${manifest.versionName}") } } }
        if (ui.updateStatus.isNotBlank()) item { Text(ui.updateStatus, style = MaterialTheme.typography.bodySmall) }
        item {
            OutlinedButton(
                onClick = {
                    val target = ui.mirrorUrl.trim()
                    if (UpdateManifest.isHttps(target)) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                    else model.report("نشانی جایگزین ثبت نشده است؛ چون مخزن گیت‌هاب خصوصی است، یک نشانی HTTPS جایگزین وارد کنید")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("باز کردن نشانی جایگزین") }
        }
        item { Text("اگر مخزن گیت‌هاب خصوصی باشد، دریافت به‌روزرسانی از آن ممکن نیست و نشانی جایگزین استفاده می‌شود. سرور گیمینگ هرگز فایل نصبی نمی‌دهد.", style = MaterialTheme.typography.bodySmall) }
        item { OutlinedButton(model::reset, Modifier.fillMaxWidth()) { Text("پاک‌کردن کامل اطلاعات") } }
        item { Text("نسخه ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun InfoRow(title: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title); Text(value, fontWeight = FontWeight.SemiBold) }

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
