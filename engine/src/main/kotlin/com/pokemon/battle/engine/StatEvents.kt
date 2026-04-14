package com.pokemon.battle.engine

import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatType
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.Volatile

data class StatChanged(
    val target: Slot,
    val stat: StatType,
    val stages: Int,
) : GameEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        return state.withPokemon(target, pokemon.copy(statStages = pokemon.statStages.withChange(stat, stages)))
    }
}

data class TypeChanged(
    val target: Slot,
    val newTypes: List<Type>,
) : GameEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        return state.withPokemon(target, pokemon.copy(typeOverride = newTypes))
    }
}

data class VolatileAdded(
    val target: Slot,
    val volatile: Volatile,
) : GameEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        return state.withPokemon(target, pokemon.copy(volatiles = pokemon.volatiles + volatile))
    }
}

data class VolatileRemoved(
    val target: Slot,
    val volatile: Volatile,
) : GameEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        return state.withPokemon(target, pokemon.copy(volatiles = pokemon.volatiles - volatile))
    }
}
