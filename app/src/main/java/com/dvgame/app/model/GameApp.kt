package com.dvgame.app.model

data class GameApp(
    val id: String,
    val name: String,
    val packages: List<String>,
    val enabled: Boolean = true,
    val isInstalled: Boolean = false,
)

enum class TrafficMode { GAME_SPLIT, GAME_LOCK }
