package com.pokemon.battle.engine

import com.pokemon.battle.model.*

data class StatusApplied(
    val target: Slot,
    val status: StatusCondition
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        return state.withPokemon(target, pokemon.copy(status = status))
    }
}

data class StatusDamage(
    val target: Slot,
    val amount: Int,
    val source: StatusCondition
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp - amount).coerceAtLeast(0)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}

data class StatusCleared(
    val target: Slot,
    val status: StatusCondition
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newVolatiles = when (status) {
            StatusCondition.SLEEP -> pokemon.volatiles.filterNot { it is Volatile.Sleep }.toSet()
            else -> pokemon.volatiles
        }
        return state.withPokemon(target, pokemon.copy(status = null, volatiles = newVolatiles))
    }
}
