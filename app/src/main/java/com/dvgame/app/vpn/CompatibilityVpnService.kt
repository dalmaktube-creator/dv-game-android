package com.dvgame.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dvgame.app.MainActivity
import com.dvgame.app.model.TunnelStatus
import com.v2ray.ang.service.TProxyService
import go.Seq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

internal object CompatibilityTunnelState {
    val status = kotlinx.coroutines.flow.MutableStateFlow<TunnelStatus>(TunnelStatus.Down)
    val telemetry = kotlinx.coroutines.flow.MutableStateFlow(TunnelTelemetry())
}

class CompatibilityVpnService : android.net.VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var core: CoreController? = null
    private var tproxy: TProxyService? = null
    private var statsJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification("در حال آماده‌سازی موتور بازی"))
        Seq.setContext(applicationContext)
        Libv2ray.initCoreEnv(filesDir.absolutePath, "")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel(stopService = true)
            ACTION_CONNECT -> {
                val raw = intent.getStringExtra(EXTRA_CONFIG)
                val pkg = intent.getStringExtra(EXTRA_PACKAGE)
                val validUntil = intent.getLongExtra(EXTRA_VALID_UNTIL, 0)
                if (raw.isNullOrBlank() || pkg.isNullOrBlank()) fail("اطلاعات اتصال ناقص است")
                else scope.launch { connect(raw, pkg, validUntil) }
            }
            else -> scope.launch {
                val saved = SecureTunnelStore(this@CompatibilityVpnService).load()
                if (saved == null) fail("اتصال ذخیره‌شده معتبر نیست")
                else connect(saved.config, saved.packageName, saved.validUntilMs)
            }
        }
        return START_STICKY
    }

    private suspend fun connect(raw: String, packageName: String, validUntil: Long) {
        CompatibilityTunnelState.status.value = TunnelStatus.Connecting
        runCatching {
            require(validUntil > System.currentTimeMillis()) { "اعتبار محلی اتصال پایان یافته است" }
            stopEngineOnly()
            val wg = parseWireGuardCompatConfig(raw)
            val routed = buildSet {
                add(packageName)
                ESSENTIAL_GAME_SERVICES.filterTo(this) { isInstalled(it) }
            }
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val underlying = connectivity.activeNetwork
            val builder = Builder()
                .setSession("DV Game UDP Compatibility")
                .setMtu(1280)
                .addAddress("10.10.0.2", 30)
                .addRoute("0.0.0.0", 0)
            wg.dnsServers.forEach(builder::addDnsServer)
            routed.forEach(builder::addAllowedApplication)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
            tun = requireNotNull(builder.establish()) { "ساخت رابط VPN ناموفق بود" }
            if (underlying != null) setUnderlyingNetworks(arrayOf(underlying))
            watchUnderlyingNetwork(connectivity)

            core = Libv2ray.newCoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(code: Long, message: String?): Long = 0
            }).also { it.startLoop(buildXrayWireGuardConfig(wg), 0) }
            tproxy = TProxyService(this, requireNotNull(tun)).also { it.start() }
            SecureTunnelStore(this).save(raw, packageName, validUntil)
            CompatibilityTunnelState.telemetry.value = TunnelTelemetry(
                routedPackages = routed.size,
                engineName = "WireGuard UDP Compatibility",
            )
            CompatibilityTunnelState.status.value = TunnelStatus.Up(packageName)
            updateNotification("فقط $packageName داخل تونل است")
            startStats(routed.size)
        }.onFailure { fail(it.message ?: "راه‌اندازی موتور سازگار ناموفق بود") }
    }

    private fun startStats(routedPackages: Int) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                val totals = runCatching { parseStats(core?.queryAllOutboundTrafficStats().orEmpty()) }.getOrDefault(0L to 0L)
                CompatibilityTunnelState.telemetry.value = TunnelTelemetry(
                    rxBytes = totals.second,
                    txBytes = totals.first,
                    routedPackages = routedPackages,
                    engineName = "WireGuard UDP Compatibility",
                )
                delay(1500)
            }
        }
    }

    private fun parseStats(value: String): Pair<Long, Long> {
        var up = 0L; var down = 0L
        value.split(';').forEach { entry ->
            val parts = entry.split(',', limit = 3)
            val amount = parts.getOrNull(2)?.toLongOrNull() ?: return@forEach
            when (parts.getOrNull(1)?.lowercase()) {
                "uplink" -> up += amount
                "downlink" -> down += amount
            }
        }
        return up to down
    }

    private fun watchUnderlyingNetwork(cm: ConnectivityManager) {
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { setUnderlyingNetworks(arrayOf(network)) }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    private fun stopEngineOnly() {
        statsJob?.cancel(); statsJob = null
        tproxy?.stop(); tproxy = null
        runCatching { core?.stopLoop() }; core = null
        runCatching { tun?.close() }; tun = null
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        networkCallback = null
    }

    private fun stopTunnel(stopService: Boolean) {
        stopEngineOnly()
        CompatibilityTunnelState.telemetry.value = TunnelTelemetry()
        CompatibilityTunnelState.status.value = TunnelStatus.Down
        if (stopService) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    private fun fail(message: String) {
        stopEngineOnly()
        CompatibilityTunnelState.status.value = TunnelStatus.Error(message)
        CompatibilityTunnelState.telemetry.value = TunnelTelemetry(engineName = "WireGuard UDP Compatibility")
        updateNotification(message)
    }

    override fun onRevoke() { SecureTunnelStore(this).clear(); stopTunnel(true); super.onRevoke() }
    override fun onDestroy() { stopEngineOnly(); scope.cancel(); super.onDestroy() }

    private fun isInstalled(packageName: String) = runCatching {
        packageManager.getApplicationInfo(packageName, 0)
    }.isSuccess

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "DV Game tunnel", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("DV Game")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val ACTION_CONNECT = "com.dvgame.app.CONNECT_COMPAT"
        private const val ACTION_STOP = "com.dvgame.app.STOP_COMPAT"
        private const val EXTRA_CONFIG = "config"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_VALID_UNTIL = "validUntil"
        private const val CHANNEL_ID = "dv_game_tunnel"
        private const val NOTIFICATION_ID = 201

        fun connect(context: Context, config: String, packageName: String, validUntil: Long) {
            val intent = Intent(context, CompatibilityVpnService::class.java).setAction(ACTION_CONNECT)
                .putExtra(EXTRA_CONFIG, config).putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_VALID_UNTIL, validUntil)
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CompatibilityVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
