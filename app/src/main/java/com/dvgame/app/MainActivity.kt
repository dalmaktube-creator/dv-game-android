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
        Scaffold(topBar = { TopAppBar(title = { Column {
            Text("DV Game", fontWeight = FontWeight.Bold)
            Text("فقط بازی‌های تأییدشده داخل تونل", style = MaterialTheme.typography.labelSmall)
        } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { StatusCard(tunnelStatus, message) {
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
                        GameCard(game, tunnelStatus !is TunnelStatus.Connecting) {
                            connectAndLaunch(sub.account, sub.profiles[profileIndex], game) { message = it }
                        }
                    }
                }
            }
        }
    }

    private fun connectAndLaunch(account: AccountInfo, profile: ServerProfile, game: InstalledGame, setMessage: (String) -> Unit) {
        val blockReason = account.connectionBlockReason()
        if (blockReason != null) {
            setMessage(blockReason)
            return
        }
        val action: () -> Unit = {
            lifecycleScope.launch { runCatching { repository.connect(profile.config, game.packageName) }
                .onSuccess {
                    val launch = packageManager.getLaunchIntentForPackage(game.packageName)
                    if (launch == null) setMessage("اجرای بازی ممکن نیست") else {
                        setMessage("متصل؛ در حال اجرای ${game.name}")
                        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }.onFailure { setMessage(it.message ?: "اتصال ناموفق بود") } }
        }
        val permission = VpnService.prepare(this)
        if (permission == null) action() else { afterVpnPermission = action; vpnPermission.launch(permission) }
    }
}

@Composable
private fun StatusCard(status: TunnelStatus, message: String, disconnect: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(when (status) { TunnelStatus.Down -> "آماده"; TunnelStatus.Connecting -> "در حال اتصال";
                    is TunnelStatus.Up -> "متصل"; is TunnelStatus.Error -> "خطای اتصال" }, fontWeight = FontWeight.Bold)
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            if (status is TunnelStatus.Up) TextButton(onClick = disconnect) { Text("قطع") }
        }
    }
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
