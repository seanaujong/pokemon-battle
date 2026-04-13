package com.pokemon.battle.model

enum class Weather { SUN, RAIN, SANDSTORM, HAIL }

data class FieldState(
    val weather: Weather? = null,
    val weatherTurnsRemaining: Int = 0,
)
