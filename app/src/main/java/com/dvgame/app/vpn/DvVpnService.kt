package com.dvgame.app.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dvgame.app.DvApplication
import com.dvgame.app.MainActivity
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class DvVpnService : VpnService(), PlatformInterfaceImpl, CommandServerHandler {
    companion object {
        private const val TAG = "DvVpnService"
        const val ACTION_CONNECT = "com.dvgame.app.CONNECT"
        const val ACTION_DISCONNECT = "com.dvgame.app.DISCONNECT"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_PACKAGES = "packages"
        private const val NOTIFICATION_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandServer: CommandServer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG) ?: run {
                    BoxController.get(application).setError("کانفیگ دریافت نشد")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val packages = intent.getStringArrayExtra(EXTRA_PACKAGES)?.toSet() ?: emptySet()
                startForegroundCompat("در حال اتصال...")
                BoxController.get(application).state.value = TunnelState.Starting
                scope.launch { startService(config, packages) }
            }
            ACTION_DISCONNECT -> {
                scope.launch { stopService() }
            }
            else -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startService(config: String, packages: Set<String>) {
        try {
            val cs = CommandServer(this, this)
            cs.start()
            commandServer = cs

            val override = io.nekohasekai.libbox.OverrideOptions().apply {
                if (packages.isNotEmpty()) {
                    includePackage = StringIteratorImpl(packages.iterator())
                }
            }
            cs.startOrReloadService(config, override)
            BoxController.get(application).state.value = TunnelState.Started
            withContext(Dispatchers.Main) {
                startForegroundCompat("VPN فعال است")
            }
        } catch (e: Exception) {
            Log.e(TAG, "startService failed", e)
            BoxController.get(application).setError(e.message ?: "خطای نامشخص")
            stopService()
        }
    }

    private suspend fun stopService() {
        val currentState = BoxController.get(application).state.value
        if (currentState is TunnelState.Stopped || currentState is TunnelState.Stopping) return

        BoxController.get(application).state.value = TunnelState.Stopping
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        fileDescriptor?.close()
        fileDescriptor = null
        BoxController.get(application).state.value = TunnelState.Stopped
        withContext(Dispatchers.Main) {
            @Suppress("DEPRECATION")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.launch { stopService() }
    }

    override fun onRevoke() {
        scope.launch { stopService() }
    }

    // PlatformInterfaceImpl
    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("مجوز VPN دریافت نشد")

        val builder = Builder()
            .setSession("DV Game")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        var hasIPv4 = false
        var hasIPv6 = false

        val inet4 = options.inet4Address
        while (inet4.hasNext()) {
            val addr = inet4.next()
            builder.addAddress(addr.address(), addr.prefix())
            hasIPv4 = true
        }

        val inet6 = options.inet6Address
        while (inet6.hasNext()) {
            val addr = inet6.next()
            builder.addAddress(addr.address(), addr.prefix())
            hasIPv6 = true
        }

        if (options.autoRoute) {
            val dnsServers = options.dnsServerAddress
            while (dnsServers.hasNext()) {
                builder.addDnsServer(dnsServers.next())
            }

            if (hasIPv4) builder.addRoute("0.0.0.0", 0)
            if (hasIPv6) builder.addRoute("::", 0)

            val includePkg = options.includePackage
            while (includePkg.hasNext()) {
                try {
                    builder.addAllowedApplication(includePkg.next())
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "package not found: ${e.message}")
                }
            }

            val excludePkg = options.excludePackage
            while (excludePkg.hasNext()) {
                try {
                    builder.addDisallowedApplication(excludePkg.next())
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "package not found: ${e.message}")
                }
            }
        }

        val pfd = builder.establish() ?: error("ایجاد TUN ناموفق بود")
        fileDescriptor = pfd
        return pfd.fd
    }

    // CommandServerHandler
    override fun serviceStop() {
        fileDescriptor?.close()
        fileDescriptor = null
        runCatching { commandServer?.close() }
        commandServer = null
        BoxController.get(application).state.value = TunnelState.Stopped
        scope.launch {
            withContext(Dispatchers.Main) {
                @Suppress("DEPRECATION")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun serviceReload() {}
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun setSystemProxyEnabled(isEnabled: Boolean) {}
    override fun triggerNativeCrash() {}
    override fun writeDebugMessage(message: String?) { Log.d(TAG, message ?: "") }
    override fun connectSSHAgent(): Int = -1

    private fun startForegroundCompat(text: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(this, 0, intent, flags)
        return NotificationCompat.Builder(this, DvApplication.VPN_CHANNEL)
            .setContentTitle("DV Game")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
