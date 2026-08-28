package com.dvgame.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.getSystemService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions

class DvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this

        val baseDir = filesDir.apply { mkdirs() }
        val workDir = (getExternalFilesDir(null) ?: filesDir).apply { mkdirs() }
        val tmpDir = cacheDir.apply { mkdirs() }

        runCatching {
            Libbox.setup(SetupOptions().apply {
                basePath = baseDir.path
                workingPath = workDir.path
                tempPath = tmpDir.path
                logMaxLines = 3000
                debug = BuildConfig.DEBUG
                appVersion = BuildConfig.VERSION_CODE.toString()
                appMarketingVersion = BuildConfig.VERSION_NAME
            })
        }.onFailure { it.printStackTrace() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService<NotificationManager>()?.createNotificationChannel(
                NotificationChannel(VPN_CHANNEL, "DV Game VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        lateinit var instance: DvApplication
            private set
        const val VPN_CHANNEL = "dv-game-vpn"
    }
}
