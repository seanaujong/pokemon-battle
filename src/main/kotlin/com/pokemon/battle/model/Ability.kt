package com.pokemon.battle.model

enum class Ability {
    // Starters
    BLAZE, OVERGROW, TORRENT,
    // Weather immunity
    SAND_VEIL, SAND_RUSH, SAND_FORCE,
    SNOW_CLOAK, ICE_BODY,
    // Switch-in triggers
    INTIMIDATE, DRIZZLE,
    // Damage immunity
    LEVITATE,
}

private val SANDSTORM_IMMUNE = setOf(Ability.SAND_VEIL, Ability.SAND_RUSH, Ability.SAND_FORCE)
private val HAIL_IMMUNE = setOf(Ability.SNOW_CLOAK, Ability.ICE_BODY)

/** Returns true if [ability] grants immunity to damage from [weather]. */
fun isWeatherImmune(ability: Ability?, weather: Weather): Boolean = when (weather) {
    Weather.SANDSTORM -> ability in SANDSTORM_IMMUNE
    Weather.HAIL -> ability in HAIL_IMMUNE
    else -> false
}
