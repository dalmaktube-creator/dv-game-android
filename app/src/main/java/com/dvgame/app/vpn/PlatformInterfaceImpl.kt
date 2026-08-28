package com.dvgame.app.vpn

import android.os.Build
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState

interface PlatformInterfaceImpl : PlatformInterface {
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun autoDetectInterfaceControl(fd: Int) {}
    override fun openTun(options: TunOptions): Int = error("not implemented")
    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner = error("not supported")
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}
    override fun getInterfaces(): NetworkInterfaceIterator = object : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = false
        override fun next(): NetworkInterface = error("no interfaces")
    }
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() {}
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() {}
    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = error("not supported")
    override fun readSystemSSHHostKey(): String = error("not supported")
    override fun lookupSFTPServer(): String = error("not supported")
    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = error("not supported")
    override fun lookupUser(username: String?): PlatformUser = error("not supported")
    override fun registerMyInterface(name: String?) {}
}

class StringIteratorImpl(private val items: List<String>) : StringIterator {
    private var index = 0
    override fun len(): Int = items.size
    override fun hasNext(): Boolean = index < items.size
    override fun next(): String = items[index++]
}
