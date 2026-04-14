package com.pokemon.battle.engine

import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Slot

data class ItemHealing(
    val target: Slot,
    val amount: Int,
    val item: Item,
) : GameEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp + amount).coerceAtMost(pokemon.maxHp)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}

data class ItemConsumed(
    val target: Slot,
    val item: Item,
) : GameEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        return state.withPokemon(target, pokemon.copy(item = null))
    }
}

/** HP loss caused by a held item (e.g. Life Orb recoil). */
data class ItemDamage(
    val target: Slot,
    val amount: Int,
    val item: Item,
) : GameEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp - amount).coerceAtLeast(0)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}
