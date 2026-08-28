package com.dvgame.app

import android.app.Application
import com.dvgame.app.vpn.TunnelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DvGameApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var tunnelRepository: TunnelRepository
        private set

    override fun onCreate() {
        super.onCreate()
        tunnelRepository = TunnelRepository(this, applicationScope)
    }
}
