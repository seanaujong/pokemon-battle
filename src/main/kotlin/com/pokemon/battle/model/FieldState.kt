package com.pokemon.battle.model

enum class Weather { SUN, RAIN, SANDSTORM, HAIL }

data class FieldState(
    val weather: Weather? = null,
    val weatherTurnsRemaining: Int = 0,
    /** 0 = inactive. Non-zero inverts speed-tiebreak order in [com.pokemon.battle.engine.resolveMoveOrder]. */
    val trickRoomTurnsRemaining: Int = 0,
)
