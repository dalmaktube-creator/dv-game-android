package com.dvgame.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.dvgame.app.data.GameScanner
import com.dvgame.app.data.SelectionStore
import com.dvgame.app.model.GameApp
import com.dvgame.app.model.TrafficMode
import com.dvgame.app.net.SubscriptionClient
import com.dvgame.app.ui.DvTheme
import com.dvgame.app.vpn.WireGuardController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var controller: WireGuardController
    private var pendingConnect: (() -> Unit)? = null

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) pendingConnect?.invoke()
        pendingConnect = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = WireGuardController(this)
        setContent {
            DvTheme {
                Surface(Modifier.fillMaxSize()) {
                    DvGameScreen(
                        requestConnect = { action ->
                            val permission = VpnService.prepare(this)
                            if (permission == null) action()
                            else {
                                pendingConnect = action
                                vpnPermission.launch(permission)
                            }
                        },
                        connect = controller::connect,
                        disconnect = controller::disconnect,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DvGameScreen(
    requestConnect: ((() -> Unit)) -> Unit,
    connect: (String, Set<String>) -> Unit,
    disconnect: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val store = remember { SelectionStore(context) }
    var games by remember { mutableStateOf(emptyList<GameApp>()) }
    var mode by remember { mutableStateOf(store.loadMode()) }
    var subscriptionUrl by remember { mutableStateOf("") }
    var config by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("لینک اشتراک را وارد کنید") }

    LaunchedEffect(Unit) {
        val selected = store.loadPackages()
        games = withContext(Dispatchers.IO) { GameScanner.installedGames(context) }
            .map { it.copy(selected = it.packageName in selected) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DV Game", fontWeight = FontWeight.Bold)
                        Text("فقط بازی، داخل تونل", style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        if (connected) {
                            busy = true
                            activity.lifecycleScope.launch(Dispatchers.IO) {
                                runCatching { disconnect() }
                                    .onSuccess {
                                        withContext(Dispatchers.Main) {
                                            connected = false; message = "تونل قطع شد"
                                        }
                                    }
                                    .onFailure { e ->
                                        withContext(Dispatchers.Main) { message = e.message ?: "خطا در قطع اتصال" }
                                    }
                                withContext(Dispatchers.Main) { busy = false }
                            }
                        } else {
                            val selected = games.filter { it.selected }.map { it.packageName }.toSet()
                            val ready = config
                            if (ready == null) message = "ابتدا کانفیگ را دریافت کنید"
                            else if (selected.isEmpty()) message = "حداقل یک بازی را انتخاب کنید"
                            else requestConnect {
                                busy = true
                                activity.lifecycleScope.launch(Dispatchers.IO) {
                                    runCatching { connect(ready, selected) }
                                        .onSuccess {
                                            withContext(Dispatchers.Main) {
                                                connected = true; message = "فقط بازی‌های انتخاب‌شده از VPN عبور می‌کنند"
                                                if (mode == TrafficMode.GAME_LOCK) {
                                                    context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                                                }
                                            }
                                        }
                                        .onFailure { e ->
                                            withContext(Dispatchers.Main) { message = e.message ?: "اتصال ناموفق بود" }
                                        }
                                    withContext(Dispatchers.Main) { busy = false }
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
                    colors = if (connected) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.buttonColors(),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Text(if (connected) "قطع اتصال" else "اتصال بازی‌ها")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                StatusCard(connected = connected, message = message)
            }
            item {
                SectionCard("کانفیگ") {
                    OutlinedTextField(
                        value = subscriptionUrl,
                        onValueChange = { subscriptionUrl = it.trim() },
                        label = { Text("Subscription link") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            busy = true
                            activity.lifecycleScope.launch(Dispatchers.IO) {
                                runCatching { SubscriptionClient.fetch(subscriptionUrl) }
                                    .onSuccess {
                                        config = it
                                        withContext(Dispatchers.Main) { message = "کانفیگ آماده است" }
                                    }
                                    .onFailure { e ->
                                        withContext(Dispatchers.Main) { message = e.message ?: "دریافت کانفیگ ناموفق بود" }
                                    }
                                withContext(Dispatchers.Main) { busy = false }
                            }
                        },
                        enabled = subscriptionUrl.isNotBlank() && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("دریافت و بررسی کانفیگ") }
                    Text(
                        "در نسخه MVP کانفیگ و کلید خصوصی ذخیره نمی‌شود.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            item {
                SectionCard("حالت ترافیک") {
                    ModeRow(
                        selected = mode,
                        onChange = {
                            mode = it
                            store.saveMode(it)
                        },
                    )
                    if (mode == TrafficMode.GAME_LOCK) {
                        Text(
                            "بعد از اتصال، تنظیمات VPN اندروید باز می‌شود؛ DV Game را Always-on کنید و گزینه Block connections without VPN را روشن کنید.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("بازی‌های نصب‌شده", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${games.count { it.selected }} انتخاب", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (games.isEmpty()) {
                item {
                    Text(
                        "بازی نصب‌شده‌ای که Android آن را در دسته Game قرار داده باشد پیدا نشد.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(games, key = { it.packageName }) { game ->
                    GameRow(game) {
                        games = games.map { if (it.packageName == game.packageName) it.copy(selected = !it.selected) else it }
                        store.savePackages(games.filter { it.selected }.map { it.packageName }.toSet())
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

@Composable
private fun StatusCard(connected: Boolean, message: String) {
    val tint = if (connected) Color(0xFF46A171) else MaterialTheme.colorScheme.primary
    Surface(shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = 0.12f)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(tint, RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (connected) "متصل" else "آماده‌سازی", fontWeight = FontWeight.Bold)
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun ModeRow(selected: TrafficMode, onChange: (TrafficMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == TrafficMode.GAME_SPLIT,
            onClick = { onChange(TrafficMode.GAME_SPLIT) },
            label = { Text("Game Split") },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = selected == TrafficMode.GAME_LOCK,
            onClick = { onChange(TrafficMode.GAME_LOCK) },
            label = { Text("Game Lock") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GameRow(game: GameApp, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(game.label.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(game.label, fontWeight = FontWeight.Medium)
                Text(game.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(checked = game.selected, onCheckedChange = { onToggle() })
        }
    }
}
