package com.dvgame.app

import android.app.Application
import com.dvgame.app.vpn.TunnelRepository
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.Locale

class DvGameApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var tunnelRepository: TunnelRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val working = getExternalFilesDir(null) ?: filesDir
        Libbox.setup(SetupOptions().apply {
            basePath = filesDir.absolutePath
            workingPath = working.absolutePath
            tempPath = cacheDir.absolutePath
            fixAndroidStack = true
            logMaxLines = 500
            debug = BuildConfig.DEBUG
        })
        Libbox.setLocale(Locale.getDefault().toLanguageTag())
        tunnelRepository = TunnelRepository(this, applicationScope)
    }
}
