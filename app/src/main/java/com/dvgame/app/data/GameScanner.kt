package com.dvgame.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.dvgame.app.model.GameApp

object GameScanner {
    fun installedGames(context: Context): List<GameApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
        }

        return apps.asSequence()
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != context.packageName }
            .filter { it.category == ApplicationInfo.CATEGORY_GAME }
            .distinctBy { it.packageName }
            .map {
                GameApp(
                    label = pm.getApplicationLabel(it).toString(),
                    packageName = it.packageName,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
