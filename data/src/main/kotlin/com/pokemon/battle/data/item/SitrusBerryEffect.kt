package com.pokemon.battle.data.item

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.ItemConsumed
import com.pokemon.battle.engine.ItemHealing
import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.engine.item.ItemEffect
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Slot

/**
 * Sitrus Berry: restores 25% max HP when the holder's HP drops to or below 50%. Consumed
 * after use.
 *
 * Triggers at most once per damage event via [onHpThresholdCrossed]. The caller provides
 * the previous HP; this effect compares it against state's current HP to detect the cross.
 */
object SitrusBerryEffect : ItemEffect {
    override val item = Item.SITRUS_BERRY

    override fun onHpThresholdCrossed(
        state: BattleState,
        slot: Slot,
        previousHp: Int,
        abilities: AbilityRegistry,
    ): List<GameEvent> {
        val holder = state.pokemonFor(slot)
        val threshold = holder.maxHp / 2
        if (previousHp <= threshold) return emptyList()
        if (holder.currentHp > threshold) return emptyList()
        if (holder.currentHp <= 0) return emptyList() // faint cases handled elsewhere
        val healing = holder.maxHp / HEAL_DIVISOR
        return listOf(
            ItemHealing(slot, healing, Item.SITRUS_BERRY),
            ItemConsumed(slot, Item.SITRUS_BERRY),
        )
    }

    private const val HEAL_DIVISOR = 4 // 1/4 max HP = 25%
}
