package com.pokemon.battle.engine.item

import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.ItemConsumed
import com.pokemon.battle.engine.ItemHealing
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot

/**
 * Sitrus Berry: restores 25% max HP when the holder's HP drops to or below 50%. Consumed
 * after use.
 *
 * Triggers at most once per damage event via [onHpThresholdCrossed]. Caller provides the
 * previous and current HP; this effect decides whether the threshold was crossed.
 */
object SitrusBerryEffect : ItemEffect {
    override val item = Item.SITRUS_BERRY

    override fun onHpThresholdCrossed(
        holder: PokemonState,
        slot: Slot,
        state: com.pokemon.battle.engine.BattleState,
        previousHp: Int,
        currentHp: Int,
    ): List<BattleEvent> {
        val threshold = holder.maxHp / 2
        if (previousHp <= threshold) return emptyList()
        if (currentHp > threshold) return emptyList()
        if (currentHp <= 0) return emptyList() // faint cases handled elsewhere
        val healing = holder.maxHp / HEAL_DIVISOR
        return listOf(
            ItemHealing(slot, healing, Item.SITRUS_BERRY),
            ItemConsumed(slot, Item.SITRUS_BERRY),
        )
    }

    override fun renderHealing(
        amount: Int,
        pokemonName: String,
    ): String = "$pokemonName ate its Sitrus Berry and restored HP!"

    override fun renderConsumed(pokemonName: String): String = "" // already covered by the healing line

    private const val HEAL_DIVISOR = 4 // 1/4 max HP = 25%
}
