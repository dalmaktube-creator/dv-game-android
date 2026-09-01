package com.dvgame.app.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

internal object CompatibilityTunnelState {
    val status = kotlinx.coroutines.flow.MutableStateFlow<TunnelStatus>(TunnelStatus.Idle)
    val telemetry = kotlinx.coroutines.flow.MutableStateFlow(TunnelTelemetry())
}

private data class ConnectionRequest(
    val rawConfig: String,
    val packageName: String,
    val validUntilMs: Long,
    val serverName: String = "",
)

class CompatibilityVpnService : android.net.VpnService(), PlatformInterface, CommandServerHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null
    private var tun: ParcelFileDescriptor? = null
    private var commandServer: CommandServer? = null
    private var defaultListener: InterfaceUpdateListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var requestedConnection: ConnectionRequest? = null
    private var activePackage = ""
    private var boundNetwork: Network? = null
    private val observedCapabilities = ConcurrentHashMap<Network, NetworkCapabilities>()

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification("در حال آماده‌سازی موتور اتصال"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop()
            ACTION_CONNECT -> {
                val raw = intent.getStringExtra(EXTRA_CONFIG)
                val pkg = intent.getStringExtra(EXTRA_PACKAGE)
                val validUntil = intent.getLongExtra(EXTRA_VALID_UNTIL, 0)
                val server = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
                if (raw.isNullOrBlank() || pkg.isNullOrBlank()) {
                    publishBlocked("اطلاعات اتصال ناقص است")
                } else {
                    startConnection(ConnectionRequest(raw, pkg, validUntil, server), reconnect = false)
                }
            }
            else -> scope.launch {
                val saved = SecureTunnelStore(this@CompatibilityVpnService).load()
                if (saved == null) publishBlocked("اتصال ذخیره‌شده معتبر نیست")
                else startConnection(
                    ConnectionRequest(saved.config, saved.packageName, saved.validUntilMs, ""),
                    reconnect = true,
                )
            }
        }
        return START_STICKY
    }

    private fun startConnection(request: ConnectionRequest, reconnect: Boolean) {
        requestedConnection = request
        connectionJob?.cancel()
        reconnectJob?.cancel()
        connectionJob = scope.launch {
            try {
                operationMutex.withLock { connectWithRetry(request, reconnect) }
            } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
                val current = CompatibilityTunnelState.status.value
                if (current is TunnelStatus.Preparing || current is TunnelStatus.Starting || current is TunnelStatus.Reconnecting) {
                    CompatibilityTunnelState.status.value = TunnelStatus.Idle
                }
                throw cancellation
            }
        }
    }

    private suspend fun connectWithRetry(request: ConnectionRequest, reconnect: Boolean) {
        val parsed = try {
            validateRequest(request)
            parseWireGuardCompatConfig(request.rawConfig)
        } catch (error: Throwable) {
            publishBlocked(humanReadableTunnelError(error).takeIf { error !is IllegalArgumentException } ?: error.message.orEmpty())
            return
        }

        var lastError: Throwable? = null
        repeat(MAX_CONNECT_ATTEMPTS) { attemptIndex ->
            try {
                validateRequest(request)
                CompatibilityTunnelState.status.value = when {
                    reconnect || attemptIndex > 0 -> TunnelStatus.Reconnecting(attemptIndex + 1, 0, "در حال بازیابی اتصال")
                    else -> TunnelStatus.Preparing
                }
                updateNotification(if (reconnect || attemptIndex > 0) "در حال بازیابی اتصال" else "در حال آماده‌سازی اتصال")

                val network = requireUsableNetwork()
                val capabilities = getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network)
                    ?: error("اطلاعات اتصال شبکه در دسترس نیست")
                val candidates = runInterruptible(Dispatchers.IO) {
                    resolveEndpointIpv4Candidates(parsed) { host -> network.getAllByName(host) }
                }
                val endpointAddress = selectEndpointAddress(candidates, attemptIndex)
                val keepalive = effectiveKeepaliveSeconds(parsed.persistentKeepalive, capabilities.toTransport())
                val config = buildLibboxWireGuardConfig(
                    config = parsed,
                    packageName = request.packageName,
                    resolvedEndpointAddress = endpointAddress,
                    keepaliveSeconds = keepalive,
                    packetPath = PacketPathOptions(),
                )

                stopEngineOnly()
                activePackage = request.packageName
                boundNetwork = network
                CompatibilityTunnelState.status.value = TunnelStatus.Starting
                updateNotification("در حال راه‌اندازی تونل بازی")
                withTimeout(ENGINE_START_TIMEOUT_MS) {
                    runInterruptible(Dispatchers.IO) { startEngine(config, request.packageName) }
                }

                SecureTunnelStore(this).save(request.rawConfig, request.packageName, request.validUntilMs)
                CompatibilityTunnelState.telemetry.value = TunnelTelemetry(
                    routedPackages = 1,
                    engineName = "libbox mixed (gVisor UDP)",
                )
                CompatibilityTunnelState.status.value = TunnelStatus.Connected(request.packageName)
                updateNotification(connectedNotificationText(request.serverName, request.packageName))
                return
            } catch (cancelled: CancellationException) {
                stopEngineOnly()
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                stopEngineOnly()
                if (!isRetryableTunnelFailure(error)) {
                    publishBlocked(error.message ?: humanReadableTunnelError(error))
                    return
                }
                if (attemptIndex == MAX_CONNECT_ATTEMPTS - 1) return@repeat
                val delayMs = reconnectDelayMs(attemptIndex + 1)
                val message = humanReadableTunnelError(error)
                CompatibilityTunnelState.status.value = TunnelStatus.Reconnecting(attemptIndex + 1, delayMs, message)
                updateNotification("$message؛ تلاش مجدد")
                delay(delayMs)
            }
        }
        val message = humanReadableTunnelError(lastError ?: IllegalStateException("connection failed"))
        CompatibilityTunnelState.status.value = TunnelStatus.Failed(message, retryable = true)
        CompatibilityTunnelState.telemetry.value = TunnelTelemetry(engineName = "libbox mixed (gVisor UDP)")
        updateNotification(message)
    }

    private fun validateRequest(request: ConnectionRequest) {
        if (request.validUntilMs <= System.currentTimeMillis()) throw ConnectionBlockedException("اعتبار محلی اتصال پایان یافته است")
        if (!isInstalled(request.packageName)) throw ConnectionBlockedException("بازی تأییدشده روی گوشی نصب نیست")
        if (prepare(this) != null) throw ConnectionBlockedException("مجوز VPN صادر نشده است")
    }

    private fun requireUsableNetwork(): Network {
        val manager = getSystemService(ConnectivityManager::class.java)
        val candidates = buildList {
            boundNetwork?.let(::add)
            manager.activeNetwork?.let(::add)
            addAll(manager.allNetworks)
        }.distinct()
        return candidates.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.isUsableUnderlyingNetwork() == true
        } ?: error("اتصال فیزیکی اینترنت در دسترس نیست")
    }

    private fun startEngine(config: String, packageName: String) {
        val server = CommandServer(this, this)
        try {
            server.start()
            server.startOrReloadService(config, OverrideOptions().apply {
                autoRedirect = false
                includePackage = StringArray(listOf(packageName))
            })
            commandServer = server
        } catch (error: Throwable) {
            runCatching { server.closeService() }
            runCatching { server.close() }
            throw error
        }
    }

    private fun stopEngineOnly() {
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tun?.close() }
        tun = null
        stopDefaultMonitor()
        activePackage = ""
        boundNetwork = null
    }

    private fun requestStop() {
        connectionJob?.cancel()
        reconnectJob?.cancel()
        connectionJob = scope.launch {
            operationMutex.withLock {
                CompatibilityTunnelState.status.value = TunnelStatus.Stopping
                updateNotification("در حال قطع اتصال")
                requestedConnection = null
                stopEngineOnly()
                CompatibilityTunnelState.telemetry.value = TunnelTelemetry()
                CompatibilityTunnelState.status.value = TunnelStatus.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun publishBlocked(message: String) {
        stopEngineOnly()
        CompatibilityTunnelState.status.value = TunnelStatus.Blocked(message.ifBlank { "این اتصال مجاز نیست" })
        CompatibilityTunnelState.telemetry.value = TunnelTelemetry(engineName = "libbox mixed (gVisor UDP)")
        updateNotification(message.ifBlank { "اتصال متوقف شد" })
    }

    private fun scheduleNetworkReconnect(reason: String) {
        val request = requestedConnection ?: return
        if (CompatibilityTunnelState.status.value !is TunnelStatus.Connected) return
        reconnectJob?.cancel()
        CompatibilityTunnelState.status.value = TunnelStatus.Reconnecting(1, NETWORK_DEBOUNCE_MS, reason)
        updateNotification(reason)
        reconnectJob = scope.launch {
            delay(NETWORK_DEBOUNCE_MS)
            operationMutex.withLock { connectWithRetry(request, reconnect = true) }
        }
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
        boundNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
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
            setAndroidPackageNames(StringArray(packages.toList()))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        stopDefaultMonitor()
        defaultListener = listener
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Android may not have delivered capabilities/link properties yet.
                // Deliberately wait for the corresponding callbacks below.
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.isUsableUnderlyingNetwork()) {
                    observedCapabilities[network] = capabilities
                    handleDefaultNetwork(network)
                } else {
                    observedCapabilities.remove(network)
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (observedCapabilities[network]?.isUsableUnderlyingNetwork() != true) return
                reportDefaultInterface(listener, linkProperties)
                handleDefaultNetwork(network)
            }

            override fun onLost(network: Network) {
                observedCapabilities.remove(network)
                if (network == boundNetwork) {
                    runCatching { setUnderlyingNetworks(null) }
                    scheduleNetworkReconnect("شبکه قطع یا تعویض شد")
                }
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        networkCallback = callback
        boundNetwork?.let { network ->
            manager.getLinkProperties(network)?.let { reportDefaultInterface(listener, it) }
        }
    }

    private fun handleDefaultNetwork(network: Network) {
        val previous = boundNetwork
        boundNetwork = network
        runCatching { setUnderlyingNetworks(arrayOf(network)) }
        if (previous != null && previous != network) scheduleNetworkReconnect("شبکه تغییر کرد؛ اتصال در حال بازیابی است")
    }

    private fun reportDefaultInterface(listener: InterfaceUpdateListener, properties: LinkProperties) {
        val name = properties.interfaceName
        val index = name?.let { runCatching { NetworkInterface.getByName(it)?.index }.getOrNull() }
        listener.updateDefaultInterface(name.orEmpty(), index ?: -1, false, false)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) { stopDefaultMonitor() }

    private fun stopDefaultMonitor() {
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        defaultListener = null
        observedCapabilities.clear()
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val manager = getSystemService(ConnectivityManager::class.java)
        val javaInterfaces = NetworkInterface.getNetworkInterfaces().toList()
        val values = manager.allNetworks.mapNotNull { network ->
            val properties = manager.getLinkProperties(network) ?: return@mapNotNull null
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.isUsableUnderlyingNetwork()) return@mapNotNull null
            val native = javaInterfaces.firstOrNull { it.name == properties.interfaceName } ?: return@mapNotNull null
            BoxNetworkInterface().apply {
                name = native.name
                index = native.index
                mtu = runCatching { native.mtu }.getOrDefault(1500)
                addresses = StringArray(native.interfaceAddresses.map { it.toPrefix() })
                dnsServer = StringArray(properties.dnsServers.mapNotNull { it.hostAddress })
                type = when (capabilities.toTransport()) {
                    UnderlyingTransport.WIFI -> Libbox.InterfaceTypeWIFI
                    UnderlyingTransport.CELLULAR -> Libbox.InterfaceTypeCellular
                    UnderlyingTransport.ETHERNET -> Libbox.InterfaceTypeEthernet
                    UnderlyingTransport.OTHER -> Libbox.InterfaceTypeOther
                }
                var value = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                if (native.isLoopback) value = value or OsConstants.IFF_LOOPBACK
                if (native.isPointToPoint) value = value or OsConstants.IFF_POINTOPOINT
                flags = value
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        return InterfaceArray(values)
    }

    private fun NetworkCapabilities.isUsableUnderlyingNetwork(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    private fun NetworkCapabilities.toTransport(): UnderlyingTransport = when {
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UnderlyingTransport.CELLULAR
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UnderlyingTransport.WIFI
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> UnderlyingTransport.ETHERNET
        else -> UnderlyingTransport.OTHER
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun systemCertificates(): StringIterator = StringArray(emptyList())
    override fun clearDNSCache() = Unit
    override fun sendNotification(notification: Notification) = Unit

    override fun serviceStop() { requestStop() }
    override fun serviceReload() = Unit
    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply { available = false; enabled = false }
    override fun setSystemProxyEnabled(enabled: Boolean) = Unit
    override fun writeDebugMessage(message: String?) { Log.d("DVGame/libbox", message.orEmpty()) }

    override fun onRevoke() {
        SecureTunnelStore(this).clear()
        connectionJob?.cancel()
        reconnectJob?.cancel()
        requestedConnection = null
        stopEngineOnly()
        CompatibilityTunnelState.status.value = TunnelStatus.Idle
        super.onRevoke()
    }

    override fun onDestroy() {
        connectionJob?.cancel()
        reconnectJob?.cancel()
        requestedConnection = null
        stopEngineOnly()
        CompatibilityTunnelState.telemetry.value = TunnelTelemetry()
        CompatibilityTunnelState.status.value = TunnelStatus.Idle
        scope.cancel()
        super.onDestroy()
    }

    private fun isInstalled(packageName: String) = runCatching { packageManager.getApplicationInfo(packageName, 0) }.isSuccess
    private fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
        "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
    } else "${address.hostAddress}/$networkPrefixLength"

    private class StringArray(private val values: List<String>) : StringIterator {
        private var index = 0
        override fun len(): Int = values.size
        override fun hasNext(): Boolean = index < values.size
        override fun next(): String = values[index++]
    }

    private class InterfaceArray(private val values: List<BoxNetworkInterface>) : NetworkInterfaceIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < values.size
        override fun next(): BoxNetworkInterface = values[index++]
    }

    private fun notification(text: String): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DV Game tunnel", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CompatibilityVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("DV Game")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع اتصال", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun connectedNotificationText(serverName: String, packageName: String): String {
        val server = serverName.ifBlank { "سرور ذخیره‌شده" }
        return "متصل به $server • فقط ${appLabel(packageName)} داخل تونل است"
    }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    companion object {
        private const val ACTION_CONNECT = "com.dvgame.app.CONNECT_LIBBOX"
        private const val ACTION_STOP = "com.dvgame.app.STOP_LIBBOX"
        private const val EXTRA_CONFIG = "config"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_VALID_UNTIL = "validUntil"
        private const val EXTRA_SERVER_NAME = "serverName"
        private const val CHANNEL_ID = "dv_game_tunnel"
        private const val NOTIFICATION_ID = 201
        private const val MAX_CONNECT_ATTEMPTS = 2
        private const val ENGINE_START_TIMEOUT_MS = 15_000L
        private const val NETWORK_DEBOUNCE_MS = 750L

        fun connect(context: Context, config: String, packageName: String, validUntil: Long, serverName: String = "") {
            val intent = Intent(context, CompatibilityVpnService::class.java).setAction(ACTION_CONNECT)
                .putExtra(EXTRA_CONFIG, config)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_VALID_UNTIL, validUntil)
                .putExtra(EXTRA_SERVER_NAME, serverName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CompatibilityVpnService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
