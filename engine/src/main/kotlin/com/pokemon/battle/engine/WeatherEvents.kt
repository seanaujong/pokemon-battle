package com.pokemon.battle.engine

import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Weather
import kotlinx.serialization.Serializable

@Serializable
data class WeatherDamage(
    val target: Slot,
    val amount: Int,
    val weather: Weather,
) : GameEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp - amount).coerceAtLeast(0)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}

@Serializable
data class WeatherTick(
    val weather: Weather,
    val turnsRemaining: Int,
) : GameEvent {
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

@Serializable
data class WeatherSet(
    val weather: Weather,
    val turnsRemaining: Int,
) : GameEvent {
    override fun apply(state: BattleState): BattleState =
        state.copy(field = state.field.copy(weather = weather, weatherTurnsRemaining = turnsRemaining))
}

/** Sets or clears Trick Room. `turnsRemaining = 0` clears it. */
@Serializable
data class TrickRoomSet(
    val turnsRemaining: Int,
) : GameEvent {
    override fun apply(state: BattleState): BattleState = state.copy(field = state.field.copy(trickRoomTurnsRemaining = turnsRemaining))
}
