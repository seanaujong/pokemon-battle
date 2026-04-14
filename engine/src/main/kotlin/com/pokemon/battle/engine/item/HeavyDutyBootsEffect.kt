package com.pokemon.battle.engine.item

import com.pokemon.battle.model.Item
import com.pokemon.battle.model.PokemonState

/**
 * Heavy-Duty Boots: the holder ignores all entry-hazard damage and effects
 * (Stealth Rock, Spikes, Toxic Spikes, Sticky Web) when switching in. Not
 * consumed — persistent. Consulted by [com.pokemon.battle.engine.resolveHazardsOnSwitchIn].
 */
object HeavyDutyBootsEffect : ItemEffect {
    override val item = Item.HEAVY_DUTY_BOOTS

    override fun blocksHazards(holder: PokemonState): Boolean = true
}
