package com.dvgame.app

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
        var selectedConfigId by remember { mutableStateOf("") }
        val scanner = remember { GameScanner(this) }

        fun doConnect() {
            val resp = subResponse ?: return
            val config = resp.configs.find { it.id == selectedConfigId } ?: resp.configs.first()
            // Auto-include all allowed games that are installed on the phone
            val packages = resp.games
                .filter { it.enabled }
                .filter { scanner.checkPackages(it.packages).isNotEmpty() }
                .flatMap { it.packages }
                .toSet()
            if (packages.isEmpty()) {
                error = "هیچ بازی مجازی روی گوشی شما نصب نیست"
                return
            }
            error = null
            scope.launch {
                val singBoxJson = withContext(Dispatchers.IO) { SingBoxConfigBuilder.build(config.config) }
                controller.connect(singBoxJson, packages)
            }
        }

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("DV Game", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = subUrl,
                onValueChange = { subUrl = it },
                label = { Text("لینک اشتراک") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        loading = true; error = null
                        try {
                            val resp = withContext(Dispatchers.IO) { SubscriptionClient.fetch(subUrl) }
                            subResponse = resp
                            SelectionStore.get(this@MainActivity).saveSubscriptionUrl(subUrl)
                            if (selectedConfigId.isEmpty() && resp.configs.isNotEmpty())
                                selectedConfigId = resp.configs.first().id
                        } catch (e: Exception) {
                            error = e.message ?: "خطای نامشخص"
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading && subUrl.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (loading) "در حال دریافت..." else "دریافت اشتراک") }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }

            subResponse?.let { resp ->
                Spacer(Modifier.height(16.dp))
                AccountCard(resp.account)
                Spacer(Modifier.height(12.dp))

                // Server selection (only if multiple configs)
                if (resp.configs.size > 1) {
                    Text("انتخاب سرور:", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    resp.configs.forEach { cfg ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            RadioButton(
                                selected = selectedConfigId == cfg.id,
                                onClick = { selectedConfigId = cfg.id },
                            )
                            Text(" ${cfg.name.ifEmpty { cfg.location.ifEmpty { cfg.id } }}")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Game list — read-only, admin-controlled
                val allowedGames = resp.games.filter { it.enabled }
                if (allowedGames.isNotEmpty()) {
                    Text("بازی‌های مجاز:", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    val gameApps = allowedGames.map { g ->
                        GameApp(g.id, g.name, g.packages, g.enabled, scanner.checkPackages(g.packages).isNotEmpty())
                    }
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(gameApps) { game ->
                            GameRow(game)
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "هیچ بازی مجازی برای این اشتراک فعال نیست.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Connect / Disconnect button
                Button(
                    onClick = {
                        when (tunnelState) {
                            is TunnelState.Started, is TunnelState.Starting -> controller.disconnect()
                            else -> {
                                val prep = VpnService.prepare(this@MainActivity)
                                if (prep == null) doConnect()
                                else {
                                    pendingConnect = ::doConnect
                                    vpnPermission.launch(prep)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (tunnelState is TunnelState.Started)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    when (tunnelState) {
                        is TunnelState.Stopped -> Icon(Icons.Default.PlayArrow, contentDescription = null)
                        is TunnelState.Starting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        is TunnelState.Started -> Icon(Icons.Default.Stop, contentDescription = null)
                        is TunnelState.Stopping -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        is TunnelState.Error -> Icon(Icons.Default.PlayArrow, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(when (tunnelState) {
                        is TunnelState.Stopped -> "اتصال"
                        is TunnelState.Starting -> "در حال اتصال..."
                        is TunnelState.Started -> "قطع"
                        is TunnelState.Stopping -> "در حال قطع..."
                        is TunnelState.Error -> "تلاش مجدد"
                    })
                }

                (tunnelState as? TunnelState.Error)?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    @Composable
    private fun AccountCard(acc: AccountInfo) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(acc.name.ifEmpty { "حساب کاربری" }, fontWeight = FontWeight.Medium)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (acc.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    ) {
                        Text(
                            if (acc.isActive) "فعال" else "غیرفعال",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (acc.totalBytes > 0) {
                    val pct = (acc.usedBytes.toFloat() / acc.totalBytes.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().height(6.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(formatBytes(acc.remainingBytes) + " باقی‌مانده", fontSize = 12.sp)
                }
                acc.remainingDays?.let {
                    Spacer(Modifier.height(2.dp))
                    Text("$it روز باقی‌مانده", fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    private fun GameRow(game: GameApp) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (game.isInstalled) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(game.name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    if (game.isInstalled) {
                        Text(
                            "نصب است ✓",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            "نصب نیست",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (game.isInstalled) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        Text(
                            "فعال",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
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
