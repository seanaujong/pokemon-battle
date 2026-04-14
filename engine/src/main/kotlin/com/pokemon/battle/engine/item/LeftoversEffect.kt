package com.pokemon.battle.engine.item

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.ItemHealing
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Slot

/** Leftovers: restores 1/16 max HP at end of turn if below full HP. Persistent (not consumed). */
object LeftoversEffect : ItemEffect {
    override val item = Item.LEFTOVERS

    override fun endOfTurn(
        state: BattleState,
        slot: Slot,
    ): List<GameEvent> {
        val pokemon = state.pokemonFor(slot)
        if (pokemon.currentHp >= pokemon.maxHp) return emptyList()
        return listOf(ItemHealing(target = slot, amount = pokemon.maxHp / 16, item = Item.LEFTOVERS))
    }
}
