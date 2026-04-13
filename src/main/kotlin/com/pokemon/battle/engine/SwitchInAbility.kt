package com.pokemon.battle.engine

import com.pokemon.battle.model.*

/**
 * Resolves switch-in ability triggers for a Pokemon that just entered a slot.
 * Called by SwitchPhase (voluntary switches), BattleLoop (faint replacements),
 * and future forced-switch logic.
 */
fun resolveSwitchInAbility(state: BattleState, slot: Slot): List<BattleEvent> {
    val pokemon = state.pokemonFor(slot)
    return when (pokemon.ability) {
        Ability.INTIMIDATE -> {
            val events = mutableListOf<BattleEvent>(AbilityTriggered(slot, Ability.INTIMIDATE))
            for (opponentSlot in state.opponentSlots(slot)) {
                val opponent = state.pokemonFor(opponentSlot)
                if (!opponent.isFainted) {
                    events.add(StatChanged(opponentSlot, StatType.ATTACK, -1))
                }
            }
            events
        }
        Ability.DRIZZLE -> listOf(
            AbilityTriggered(slot, Ability.DRIZZLE),
            WeatherSet(Weather.RAIN, 5)
        )
        else -> emptyList()
    }
}
