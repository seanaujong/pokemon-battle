package com.pokemon.battle.engine

import com.pokemon.battle.model.*

data class WeatherDamage(
    val target: Slot,
    val amount: Int,
    val weather: Weather,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp - amount).coerceAtLeast(0)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}

data class WeatherTick(
    val weather: Weather,
    val turnsRemaining: Int,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val newField =
            if (turnsRemaining <= 0) {
                state.field.copy(weather = null, weatherTurnsRemaining = 0)
            } else {
                state.field.copy(weatherTurnsRemaining = turnsRemaining)
            }
        return state.copy(field = newField)
    }
}

data class WeatherSet(
    val weather: Weather,
    val turnsRemaining: Int,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState =
        state.copy(field = state.field.copy(weather = weather, weatherTurnsRemaining = turnsRemaining))
}
