package com.dvgame.app

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dvgame.app.data.GameScanner
import com.dvgame.app.data.SelectionStore
import com.dvgame.app.model.GameApp
import com.dvgame.app.net.SubscriptionClient
import com.dvgame.app.net.SubscriptionResponse
import com.dvgame.app.net.AccountInfo
import com.dvgame.app.ui.DvTheme
import com.dvgame.app.vpn.BoxController
import com.dvgame.app.vpn.SingBoxConfigBuilder
import com.dvgame.app.vpn.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var controller: BoxController
    private var pendingConnect: (() -> Unit)? = null

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) pendingConnect?.invoke()
        pendingConnect = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = BoxController.get(application)
        setContent { DvTheme { Surface(Modifier.fillMaxSize()) { DvGameScreen() } } }
    }

    @Composable
    private fun DvGameScreen() {
        val scope = rememberCoroutineScope()
        val tunnelState by controller.stateFlow.collectAsState()
        var subUrl by remember { mutableStateOf(SelectionStore.get(this).getSubscriptionUrl()) }
        var subResponse by remember { mutableStateOf<SubscriptionResponse?>(null) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var selectedGames by remember { mutableStateOf(setOf<String>()) }
        var selectedConfigId by remember { mutableStateOf("") }
        val scanner = remember { GameScanner(this) }

        fun doConnect() {
            val resp = subResponse ?: return
            val config = resp.configs.find { it.id == selectedConfigId } ?: resp.configs.first()
            val packages = resp.games.filter { it.id in selectedGames }.flatMap { it.packages }.toSet()
            scope.launch {
                val singBoxJson = withContext(Dispatchers.IO) { SingBoxConfigBuilder.build(config.config) }
                controller.connect(singBoxJson, packages)
            }
        }

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("DV Game", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = subUrl, onValueChange = { subUrl = it }, label = { Text("لینک اشتراک") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    loading = true; error = null
                    try {
                        val resp = withContext(Dispatchers.IO) { SubscriptionClient.fetch(subUrl) }
                        subResponse = resp
                        SelectionStore.get(this@MainActivity).saveSubscriptionUrl(subUrl)
                        if (selectedConfigId.isEmpty() && resp.configs.isNotEmpty()) selectedConfigId = resp.configs.first().id
                    } catch (e: Exception) { error = e.message ?: "خطای نامشخص" } finally { loading = false }
                }
            }, enabled = !loading && subUrl.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(if (loading) "در حال دریافت..." else "دریافت اشتراک") }
            error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
            subResponse?.let { resp ->
                Spacer(Modifier.height(16.dp))
                AccountCard(resp.account)
                Spacer(Modifier.height(12.dp))
                if (resp.configs.size > 1) {
                    Text("انتخاب سرور:", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    resp.configs.forEach { cfg ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { selectedConfigId = cfg.id }.padding(vertical = 4.dp)) {
                            RadioButton(selected = selectedConfigId == cfg.id, onClick = { selectedConfigId = cfg.id })
                            Text(" ${cfg.name.ifEmpty { cfg.location.ifEmpty { cfg.id } }}")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (resp.games.isNotEmpty()) {
                    Text("بازی‌ها:", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    val gameApps = resp.games.map { g -> GameApp(g.id, g.name, g.packages, g.enabled, scanner.checkPackages(g.packages).isNotEmpty()) }
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(gameApps) { game ->
                            val installed = game.isInstalled
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { if (installed) selectedGames = if (game.id in selectedGames) selectedGames - game.id else selectedGames + game.id }.padding(vertical = 6.dp)) {
                                Checkbox(checked = game.id in selectedGames, onCheckedChange = { if (installed) selectedGames = if (it) selectedGames + game.id else selectedGames - game.id }, enabled = installed)
                                Column { Text(game.name, fontSize = 15.sp); if (!installed) Text("نصب نیست", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                } else { Spacer(Modifier.weight(1f)) }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    when (tunnelState) {
                        is TunnelState.Started, is TunnelState.Starting -> controller.disconnect()
                        else -> {
                            val prep = VpnService.prepare(this@MainActivity)
                            if (prep == null) doConnect() else { pendingConnect = ::doConnect; vpnPermission.launch(prep) }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (tunnelState is TunnelState.Started) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
                    when (tunnelState) {
                        is TunnelState.Stopped -> Icon(Icons.Default.PlayArrow, contentDescription = null)
                        is TunnelState.Starting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        is TunnelState.Started -> Icon(Icons.Default.Stop, contentDescription = null)
                        is TunnelState.Stopping -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        is TunnelState.Error -> Icon(Icons.Default.PlayArrow, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(when (tunnelState) { is TunnelState.Stopped -> "اتصال"; is TunnelState.Starting -> "در حال اتصال..."; is TunnelState.Started -> "قطع"; is TunnelState.Stopping -> "در حال قطع..."; is TunnelState.Error -> "تلاش مجدد" })
                }
                (tunnelState as? TunnelState.Error)?.let { Spacer(Modifier.height(4.dp)); Text(it.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }

    @Composable
    private fun AccountCard(acc: AccountInfo) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(acc.name.ifEmpty { "حساب کاربری" }, fontWeight = FontWeight.Medium)
                    Surface(shape = RoundedCornerShape(8.dp), color = if (acc.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) {
                        Text(if (acc.isActive) "فعال" else "غیرفعال", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (acc.totalBytes > 0) {
                    val pct = (acc.usedBytes.toFloat() / acc.totalBytes.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().height(6.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(formatBytes(acc.remainingBytes) + " باقی‌مانده", fontSize = 12.sp)
                }
                acc.remainingDays?.let { Spacer(Modifier.height(2.dp)); Text("$it روز باقی‌مانده", fontSize = 12.sp) }
            }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
