package com.pokemon.battle.engine.item

import com.pokemon.battle.engine.DamageAdjustment
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.PokemonState

/**
 * Focus Sash: if the holder is at full HP and would be KO'd, survive at 1 HP and consume the item.
 */
object FocusSashEffect : ItemEffect {
    override val item = Item.FOCUS_SASH

    override fun interceptIncomingDamage(
        defender: PokemonState,
        rawDamage: Int,
    ): DamageAdjustment? {
        val atFullHp = defender.currentHp == defender.maxHp
        val wouldKo = rawDamage >= defender.currentHp
        if (!atFullHp || !wouldKo) return null
        return DamageAdjustment(adjustedDamage = defender.currentHp - 1, consumed = true)
    }
}
