package com.dvgame.app.data

import android.content.Context
import com.dvgame.app.model.TrafficMode

class SelectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("dv_game", Context.MODE_PRIVATE)

    fun loadPackages(): Set<String> =
        prefs.getStringSet("selected_packages", emptySet())?.toSet().orEmpty()

    fun savePackages(packages: Set<String>) {
        prefs.edit().putStringSet("selected_packages", packages).apply()
    }

    fun loadMode(): TrafficMode = runCatching {
        TrafficMode.valueOf(prefs.getString("traffic_mode", null) ?: "GAME_SPLIT")
    }.getOrDefault(TrafficMode.GAME_SPLIT)

    fun saveMode(mode: TrafficMode) {
        prefs.edit().putString("traffic_mode", mode.name).apply()
    }

    fun loadSubscriptionLink(): String = prefs.getString("subscription_link", "").orEmpty()

    fun saveSubscriptionLink(link: String) {
        prefs.edit().putString("subscription_link", link.trim()).apply()
    }

    fun clearSubscriptionLink() {
        prefs.edit().remove("subscription_link").apply()
    }

    fun loadCachedSubscription(): String? = prefs.getString("cached_subscription", null)

    fun saveCachedSubscription(payload: String?) {
        val editor = prefs.edit()
        if (payload.isNullOrBlank()) editor.remove("cached_subscription") else editor.putString("cached_subscription", payload)
        editor.apply()
    }

    fun loadLastGamePackage(): String? = prefs.getString("last_game_package", null)

    fun saveLastGamePackage(packageName: String?) {
        val editor = prefs.edit()
        if (packageName.isNullOrBlank()) editor.remove("last_game_package") else editor.putString("last_game_package", packageName)
        editor.apply()
    }

    fun loadLastServerId(): String? = prefs.getString("last_server_id", null)

    fun saveLastServerId(serverId: String?) {
        val editor = prefs.edit()
        if (serverId.isNullOrBlank()) editor.remove("last_server_id") else editor.putString("last_server_id", serverId)
        editor.apply()
    }

    fun loadAutoLaunchGame(): Boolean = prefs.getBoolean("auto_launch_game", true)

    fun saveAutoLaunchGame(enabled: Boolean) {
        prefs.edit().putBoolean("auto_launch_game", enabled).apply()
    }
}
