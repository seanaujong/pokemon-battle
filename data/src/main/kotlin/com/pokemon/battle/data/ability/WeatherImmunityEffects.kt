package com.pokemon.battle.data.ability

import com.pokemon.battle.engine.ability.AbilityEffect
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Weather

/** Shared implementation for abilities that only grant immunity to one weather's chip damage. */
private class WeatherImmunity(
    override val ability: Ability,
    private val immuneTo: Weather,
) : AbilityEffect {
    override fun blocksWeatherDamage(weather: Weather): Boolean = weather == immuneTo
}

// --- Sandstorm immunity ---

object SandVeilEffect : AbilityEffect by WeatherImmunity(Ability.SAND_VEIL, Weather.SANDSTORM)

object SandRushEffect : AbilityEffect by WeatherImmunity(Ability.SAND_RUSH, Weather.SANDSTORM)

object SandForceEffect : AbilityEffect by WeatherImmunity(Ability.SAND_FORCE, Weather.SANDSTORM)

// --- Hail immunity ---

object SnowCloakEffect : AbilityEffect by WeatherImmunity(Ability.SNOW_CLOAK, Weather.HAIL)

object IceBodyEffect : AbilityEffect by WeatherImmunity(Ability.ICE_BODY, Weather.HAIL)
