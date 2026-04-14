package com.pokemon.battle.engine

import com.pokemon.battle.model.Side
import com.pokemon.battle.model.SideHazard
import kotlinx.serialization.Serializable

/**
 * A hazard is set (or its layer count changes) on [side]. Stealth Rock and Sticky Web
 * ignore [layers] (always 1). Spikes use 1-3; Toxic Spikes use 1-2.
 */
@Serializable
data class HazardSet(
    val side: Side,
    val hazard: SideHazard,
    val layers: Int,
) : GameEvent {
    override fun apply(state: BattleState): BattleState = state.withHazardLayers(side, hazard, layers)
}

/** A hazard is removed from [side] (Rapid Spin, Defog, Toxic-Spikes-absorbed-by-Poison-type). */
@Serializable
data class HazardRemoved(
    val side: Side,
    val hazard: SideHazard,
) : GameEvent {
    override fun apply(state: BattleState): BattleState = state.withHazardLayers(side, hazard, 0)
}

/** HP chip dealt by an entry hazard (Stealth Rock, Spikes). Sticky Web and Toxic Spikes
 *  do stat / status changes instead and use their own events. */
@Serializable
data class HazardDamage(
    val target: com.pokemon.battle.model.Slot,
    val amount: Int,
    val hazard: SideHazard,
) : GameEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp - amount).coerceAtLeast(0)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}
