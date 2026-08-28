package com.dvgame.app.model

data class GameApp(
    val label: String,
    val packageName: String,
    val selected: Boolean = false,
)

enum class TrafficMode {
    GAME_SPLIT,
    GAME_LOCK,
}
