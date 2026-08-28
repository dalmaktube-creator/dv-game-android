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
}
