package com.dvgame.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.dvgame.app.model.ApprovedGame
import com.dvgame.app.model.InstalledGame

object GameScanner {
    fun findApprovedInstalledGames(context: Context, approved: List<ApprovedGame>): List<InstalledGame> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(launcher, 0)
        }
        val launchable = resolved.map { it.activityInfo.packageName }.toHashSet()
        return approved.flatMap { game ->
            game.packages.filter { it in launchable }.map { packageName ->
                InstalledGame(game.id, game.name, packageName)
            }
        }.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
    }
}
