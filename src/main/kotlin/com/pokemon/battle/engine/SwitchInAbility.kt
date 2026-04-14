package com.pokemon.battle.engine

import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.model.Slot

/**
 * Resolves switch-in ability triggers for a Pokemon that just entered a slot.
 * Called by SwitchPhase (voluntary switches), BattleLoop (faint replacements),
 * and self-switch moves (U-turn / Volt Switch).
 *
 * Delegates to [AbilityRegistry] — adding a new switch-in ability means a new
 * [com.pokemon.battle.engine.ability.AbilityEffect] file + registry entry, no edits here.
 */
fun resolveSwitchInAbility(
    state: BattleState,
    slot: Slot,
): List<BattleEvent> {
    val pokemon = state.pokemonFor(slot)
    return AbilityRegistry.effectFor(pokemon.ability)?.onSwitchIn(state, slot) ?: emptyList()
}
