package com.pokemon.battle.engine

import com.pokemon.battle.model.*

data class StatChanged(
    val target: Slot,
    val stat: StatType,
    val stages: Int,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        return state.withPokemon(target, pokemon.copy(statStages = pokemon.statStages.withChange(stat, stages)))
    }
}

data class TypeChanged(
    val target: Slot,
    val newTypes: List<Type>,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        return state.withPokemon(target, pokemon.copy(typeOverride = newTypes))
    }
}

data class VolatileChanged(
    val target: Slot,
    val old: Volatile,
    val new: Volatile?,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val without = pokemon.volatiles.filterNot { it == old }.toSet()
        val newVolatiles = if (new != null) without + new else without
        return state.withPokemon(target, pokemon.copy(volatiles = newVolatiles))
    }
}
