package com.dvgame.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.dvgame.app.data.GameScanner
import com.dvgame.app.model.*
import com.dvgame.app.net.SubscriptionClient
import com.dvgame.app.ui.DvTheme
import com.dvgame.app.vpn.TunnelTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val repository by lazy { (application as DvGameApplication).tunnelRepository }
    private var afterVpnPermission: (() -> Unit)? = null
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) afterVpnPermission?.invoke()
        afterVpnPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DvTheme { DvGameScreen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DvGameScreen() {
        var link by remember { mutableStateOf("") }
        var subscription by remember { mutableStateOf<DvSubscription?>(null) }
        var games by remember { mutableStateOf<List<InstalledGame>>(emptyList()) }
        var profileIndex by remember { mutableIntStateOf(0) }
        var loading by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("لینک اشتراک را وارد کنید") }
        val tunnelStatus by repository.status.collectAsState()
        val telemetry by repository.telemetry.collectAsState()
        Scaffold(topBar = { TopAppBar(title = { Column {
            Text("DV Game", fontWeight = FontWeight.Bold)
            Text("فقط بازی‌های تأییدشده داخل تونل", style = MaterialTheme.typography.labelSmall)
        } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { StatusCard(tunnelStatus, telemetry, message) {
                    lifecycleScope.launch { runCatching { repository.disconnect() }
                        .onSuccess { message = "اتصال قطع شد" }
                        .onFailure { message = it.message ?: "قطع اتصال ناموفق بود" } }
                } }
                item { Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("اشتراک", fontWeight = FontWeight.Bold); Spacer(Modifier.height(10.dp))
                        OutlinedTextField(link, { link = it.trim() }, Modifier.fillMaxWidth(),
                            label = { Text("https://example.com/sub/...") }, singleLine = true)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = {
                            loading = true
                            lifecycleScope.launch {
                                runCatching { withContext(Dispatchers.IO) { SubscriptionClient.fetch(link) } }
                                    .onSuccess { result ->
                                        subscription = result
                                        profileIndex = 0
                                        val blockReason = result.account.connectionBlockReason()
                                        if (blockReason != null) {
                                            games = emptyList()
                                            message = blockReason
                                        } else {
                                            games = withContext(Dispatchers.IO) {
                                                GameScanner.findApprovedInstalledGames(this@MainActivity, result.games)
                                            }
                                            message = if (games.isEmpty()) "هیچ بازی مجاز نصب‌شده‌ای پیدا نشد" else "اشتراک آماده است"
                                        }
                                    }.onFailure { message = it.message ?: "دریافت اشتراک ناموفق بود" }
                                loading = false
                            }
                        }, enabled = link.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth()) {
                            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text("دریافت و بررسی لینک")
                        }
                    }
                } }
                subscription?.let { sub ->
                    item {
                        Text("سرور اتصال", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(sub.profiles.size) { index ->
                                val profile = sub.profiles[index]
                                FilterChip(index == profileIndex, { profileIndex = index },
                                    label = { Text(profile.location.ifBlank { profile.name }) })
                            }
                        }
                    }
                    item { Text("بازی‌های مجاز نصب‌شده", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    if (games.isEmpty()) item { Text("بازی‌های این بخش فقط توسط ادمین پنل تعیین می‌شوند.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else items(games, key = { it.packageName }) { game ->
                        GameCard(game, !tunnelStatus.isBusy()) {
                            connectAndLaunch(sub.account, sub.profiles[profileIndex], game) { message = it }
                        }
                    }
                }
            }
        }
    }

    private fun connectAndLaunch(account: AccountInfo, profile: ServerProfile, game: InstalledGame, setMessage: (String) -> Unit) {
        val nowMs = System.currentTimeMillis()
        val blockReason = account.connectionBlockReason(nowMs)
        if (blockReason != null) { setMessage(blockReason); return }
        val restoreValidUntilMs = account.localRestoreValidUntilMs(nowMs)
        val action: () -> Unit = {
            lifecycleScope.launch { runCatching { repository.connect(profile.config, game.packageName, restoreValidUntilMs) }
                .onSuccess {
                    val launch = packageManager.getLaunchIntentForPackage(game.packageName)
                    if (launch == null) setMessage("اجرای بازی ممکن نیست") else {
                        setMessage("متصل؛ موتور سازگار UDP فعال است")
                        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }.onFailure { setMessage(it.message ?: "اتصال ناموفق بود") } }
        }
        val permission = VpnService.prepare(this)
        if (permission == null) action() else { afterVpnPermission = action; vpnPermission.launch(permission) }
    }
}

@Composable
private fun StatusCard(status: TunnelStatus, telemetry: TunnelTelemetry, message: String, disconnect: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(when (status) {
                    TunnelStatus.Idle -> "آماده"
                    TunnelStatus.Preparing -> "در حال آماده‌سازی"
                    TunnelStatus.Starting -> "در حال راه‌اندازی"
                    is TunnelStatus.Connected -> "متصل"
                    is TunnelStatus.Reconnecting -> "در حال بازیابی اتصال"
                    TunnelStatus.Stopping -> "در حال قطع"
                    is TunnelStatus.Blocked -> "اتصال مجاز نیست"
                    is TunnelStatus.Failed -> "خطای اتصال"
                }, fontWeight = FontWeight.Bold)
                Text(status.detailOr(message), style = MaterialTheme.typography.bodySmall)
                if (status is TunnelStatus.Connected) {
                    Text("↓ ${formatBytes(telemetry.rxBytes)} · ↑ ${formatBytes(telemetry.txBytes)}",
                        style = MaterialTheme.typography.labelSmall)
                    Text("${telemetry.engineName} · DNS پنل · ${telemetry.routedPackages} پکیج مجاز",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            if (status is TunnelStatus.Connected || status is TunnelStatus.Reconnecting) {
                TextButton(onClick = disconnect) { Text("قطع") }
            }
        }
    }
}

private fun TunnelStatus.isBusy(): Boolean =
    this is TunnelStatus.Preparing || this is TunnelStatus.Starting ||
        this is TunnelStatus.Reconnecting || this is TunnelStatus.Stopping

private fun TunnelStatus.detailOr(fallback: String): String = when (this) {
    is TunnelStatus.Reconnecting -> if (delayMs > 0) "$reason · تلاش بعدی تا ${delayMs / 1000.0} ثانیه" else reason
    is TunnelStatus.Blocked -> reason
    is TunnelStatus.Failed -> message
    else -> fallback
}

private fun formatBytes(value: Long): String = when {
    value >= 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024.0))
    value >= 1024 -> "%.1f KB".format(value / 1024.0)
    else -> "$value B"
}

@Composable
private fun GameCard(game: InstalledGame, enabled: Boolean, connect: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(game.name, fontWeight = FontWeight.SemiBold)
                Text(game.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = connect, enabled = enabled) { Text("اتصال و اجرا") }
        }
    }
}
