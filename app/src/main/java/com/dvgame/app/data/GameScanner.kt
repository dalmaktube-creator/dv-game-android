package com.dvgame.app.data

import android.content.Context
import android.content.pm.PackageManager

class GameScanner(private val context: Context) {
    fun isPackageInstalled(packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun checkPackages(packages: List<String>): List<String> = packages.filter { isPackageInstalled(it) }
}
