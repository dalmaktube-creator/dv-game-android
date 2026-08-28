package com.dvgame.app.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dvgame.app.MainActivity
import com.dvgame.app.model.TunnelStatus
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

internal object CompatibilityTunnelState {
    val status = kotlinx.coroutines.flow.MutableStateFlow<TunnelStatus>(TunnelStatus.Down)
    val telemetry = kotlinx.coroutines.flow.MutableStateFlow(TunnelTelemetry())
}

class CompatibilityVpnService : android.net.VpnService(), PlatformInterface, CommandServerHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var commandServer: CommandServer? = null
    private var defaultListener: InterfaceUpdateListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activePackage = ""

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification("در حال آماده‌سازی موتور libbox"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel(true)
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

    private fun connect(raw: String, packageName: String, validUntil: Long) {
        CompatibilityTunnelState.status.value = TunnelStatus.Connecting
        runCatching {
            require(validUntil > System.currentTimeMillis()) { "اعتبار محلی اتصال پایان یافته است" }
            require(isInstalled(packageName)) { "بازی تأییدشده روی گوشی نصب نیست" }
            stopEngineOnly()
            val wg = parseWireGuardCompatConfig(raw)
            val config = buildLibboxWireGuardConfig(wg, packageName)
            activePackage = packageName
            commandServer = CommandServer(this, this).also { server ->
                server.start()
                server.startOrReloadService(config, OverrideOptions().apply {
                    autoRedirect = false
                    includePackage = StringArray(listOf(packageName).iterator())
                })
            }
            SecureTunnelStore(this).save(raw, packageName, validUntil)
            CompatibilityTunnelState.telemetry.value = TunnelTelemetry(
                routedPackages = 1,
                engineName = "libbox mixed (gVisor UDP)",
            )
            CompatibilityTunnelState.status.value = TunnelStatus.Up(packageName)
            updateNotification("فقط $packageName داخل تونل است")
        }.onFailure { fail(it.message ?: "راه‌اندازی موتور libbox ناموفق بود") }
    }

    private fun stopEngineOnly() {
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tun?.close() }
        tun = null
        stopDefaultMonitor()
        activePackage = ""
    }

    private fun stopTunnel(stopService: Boolean) {
        stopEngineOnly()
        CompatibilityTunnelState.telemetry.value = TunnelTelemetry()
        CompatibilityTunnelState.status.value = TunnelStatus.Down
        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail(message: String) {
        stopEngineOnly()
        CompatibilityTunnelState.status.value = TunnelStatus.Error(message)
        CompatibilityTunnelState.telemetry.value = TunnelTelemetry(engineName = "libbox mixed (gVisor UDP)")
        updateNotification(message)
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("مجوز VPN صادر نشده است")
        val builder = Builder().setSession("DV Game libbox").setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val v4 = options.inet4Address
        while (v4.hasNext()) v4.next().let { builder.addAddress(it.address(), it.prefix()) }
        val v6 = options.inet6Address
        while (v6.hasNext()) v6.next().let { builder.addAddress(it.address(), it.prefix()) }
        if (options.autoRoute) {
            builder.addDnsServer(options.dnsServerAddress.value)
            val r4 = options.inet4RouteAddress
            var hasV4Route = false
            while (r4.hasNext()) {
                val route = r4.next(); hasV4Route = true
                if (Build.VERSION.SDK_INT >= 33) builder.addRoute(IpPrefix(route.address(), route.prefix()))
                else builder.addRoute(route.address(), route.prefix())
            }
            if (!hasV4Route) builder.addRoute("0.0.0.0", 0)
            val includes = options.includePackage
            while (includes.hasNext()) builder.addAllowedApplication(includes.next())
            val excludes = options.excludePackage
            while (excludes.hasNext()) builder.addDisallowedApplication(excludes.next())
        }
        getSystemService(ConnectivityManager::class.java).activeNetwork?.let {
            builder.setUnderlyingNetworks(arrayOf(it))
        }
        val descriptor = builder.establish() ?: error("ساخت رابط VPN ناموفق بود")
        tun = descriptor
        return descriptor.fd
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun autoDetectInterfaceControl(fd: Int) { check(protect(fd)) { "محافظت سوکت خروجی ناموفق بود" } }
    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("تشخیص مالک اتصال پشتیبانی نمی‌شود")
        val uid = getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
            ipProtocol, InetSocketAddress(sourceAddress, sourcePort), InetSocketAddress(destinationAddress, destinationPort)
        )
        if (uid == Process.INVALID_UID) error("مالک اتصال پیدا نشد")
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull().orEmpty()
            setAndroidPackageNames(StringArray(packages.iterator()))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultListener = listener
        val cm = getSystemService(ConnectivityManager::class.java)
        fun report(network: Network?) {
            val lp = network?.let(cm::getLinkProperties)
            val name = lp?.interfaceName
            val index = name?.let { runCatching { NetworkInterface.getByName(it).index }.getOrNull() }
            listener.updateDefaultInterface(name.orEmpty(), index ?: -1, false, false)
        }
        report(cm.activeNetwork)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = report(network)
            override fun onLost(network: Network) = report(cm.activeNetwork)
        }
        cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) { stopDefaultMonitor() }

    private fun stopDefaultMonitor() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        defaultListener = null
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val cm = getSystemService(ConnectivityManager::class.java)
        val javaInterfaces = NetworkInterface.getNetworkInterfaces().toList()
        val values = cm.allNetworks.mapNotNull { network ->
            val lp = cm.getLinkProperties(network) ?: return@mapNotNull null
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            val native = javaInterfaces.firstOrNull { it.name == lp.interfaceName } ?: return@mapNotNull null
            BoxNetworkInterface().apply {
                name = native.name
                index = native.index
                mtu = runCatching { native.mtu }.getOrDefault(1500)
                addresses = StringArray(native.interfaceAddresses.map { it.toPrefix() }.iterator())
                dnsServer = StringArray(lp.dnsServers.mapNotNull { it.hostAddress }.iterator())
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                var f = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                if (native.isLoopback) f = f or OsConstants.IFF_LOOPBACK
                if (native.isPointToPoint) f = f or OsConstants.IFF_POINTOPOINT
                flags = f
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        return InterfaceArray(values.iterator())
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun systemCertificates(): StringIterator = StringArray(emptyList<String>().iterator())
    override fun clearDNSCache() = Unit
    override fun sendNotification(notification: Notification) = Unit

    override fun serviceStop() { stopTunnel(true) }
    override fun serviceReload() = Unit
    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply { available = false; enabled = false }
    override fun setSystemProxyEnabled(enabled: Boolean) = Unit
    override fun writeDebugMessage(message: String?) { Log.d("DVGame/libbox", message.orEmpty()) }

    override fun onRevoke() { SecureTunnelStore(this).clear(); stopTunnel(true); super.onRevoke() }
    override fun onDestroy() { stopEngineOnly(); scope.cancel(); super.onDestroy() }

    private fun isInstalled(packageName: String) = runCatching { packageManager.getApplicationInfo(packageName, 0) }.isSuccess
    private fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
        "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
    } else "${address.hostAddress}/$networkPrefixLength"

    private class StringArray(private val values: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = values.hasNext()
        override fun next(): String = values.next()
    }
    private class InterfaceArray(private val values: Iterator<BoxNetworkInterface>) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = values.hasNext()
        override fun next(): BoxNetworkInterface = values.next()
    }

    private fun notification(text: String): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DV Game tunnel", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("DV Game").setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).build()
    }
    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val ACTION_CONNECT = "com.dvgame.app.CONNECT_LIBBOX"
        private const val ACTION_STOP = "com.dvgame.app.STOP_LIBBOX"
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
