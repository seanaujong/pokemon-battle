package com.pokemon.battle.engine

import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatType

/**
 * Emits events to clear volatiles and stat stages before a Pokemon switches out.
 * Shared by voluntary switching ([com.pokemon.battle.phase.SwitchPhase]) and
 * self-switch moves like U-turn / Volt Switch.
 */
internal fun resolveSwitchOutClearing(
    state: BattleState,
    slot: Slot,
): List<GameEvent> {
    val pokemon = state.pokemonFor(slot)
    val events = mutableListOf<GameEvent>()

    for (volatile in pokemon.volatiles) {
        events.add(VolatileRemoved(slot, volatile))
    }

    for (stat in StatType.entries) {
        val current = pokemon.statStages.forStat(stat)
        if (current != 0) {
            events.add(StatChanged(slot, stat, -current))
        }
    }

    return events
}
