package com.pokemon.battle.engine

import com.pokemon.battle.model.*

data class ItemHealing(
    val target: Slot,
    val amount: Int,
    val item: Item
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp + amount).coerceAtMost(pokemon.maxHp)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}
