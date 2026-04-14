package com.pokemon.battle.engine

import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.model.Slot

/**
 * Resolves switch-in ability triggers for a Pokemon that just entered a slot.
 * Called by SwitchPhase (voluntary switches), BattleLoop (faint replacements),
 * and self-switch moves (U-turn / Volt Switch).
 *
 * Delegates to the injected [AbilityRegistry] — adding a new switch-in ability
 * means a new [com.pokemon.battle.data.ability.AbilityEffect] file + registry
 * entry, no edits here.
 */
fun resolveSwitchInAbility(
    state: BattleState,
    slot: Slot,
    abilities: AbilityRegistry,
): List<GameEvent> {
    val pokemon = state.pokemonFor(slot)
    return abilities.effectFor(pokemon.effectiveAbility)?.onSwitchIn(state, slot) ?: emptyList()
}
