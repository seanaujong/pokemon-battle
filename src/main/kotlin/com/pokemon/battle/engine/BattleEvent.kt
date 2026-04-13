package com.pokemon.battle.engine

import com.pokemon.battle.model.*

import com.pokemon.battle.model.*

sealed interface BattleEvent {
    fun apply(state: BattleState): BattleState
}

data class MoveOrderDecided(
    val firstAttacker: Player,
    val reason: String
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state // informational
}

data class MoveAttempted(
    val attacker: Player,
    val move: Move
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state // informational
}

data class MoveFailed(
    val attacker: Player,
    val reason: String
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state // informational
}

data class DamageDealt(
    val target: Player,
    val amount: Int,
    val effectiveness: Effectiveness,
    val critical: Boolean
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp - amount).coerceAtLeast(0)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}

data class PokemonFainted(
    val player: Player
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state // HP already at 0 from DamageDealt
}

data class StatusApplied(
    val target: Player,
    val status: StatusCondition
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        return state.withPokemon(target, pokemon.copy(status = status))
    }
}

data class StatusDamage(
    val target: Player,
    val amount: Int,
    val source: StatusCondition
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp - amount).coerceAtLeast(0)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}

data class WeatherDamage(
    val target: Player,
    val amount: Int,
    val weather: Weather
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp - amount).coerceAtLeast(0)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}

data class ItemHealing(
    val target: Player,
    val amount: Int,
    val item: Item
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp + amount).coerceAtMost(pokemon.maxHp)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}

data class WeatherTick(
    val weather: Weather,
    val turnsRemaining: Int
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val newField = if (turnsRemaining <= 0) {
            state.field.copy(weather = null, weatherTurnsRemaining = 0)
        } else {
            state.field.copy(weatherTurnsRemaining = turnsRemaining)
        }
        return state.copy(field = newField)
    }
}
