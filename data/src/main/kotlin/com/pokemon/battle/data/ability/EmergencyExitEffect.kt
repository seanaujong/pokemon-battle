package com.pokemon.battle.data.ability

import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.SwitchIn
import com.pokemon.battle.engine.SwitchOut
import com.pokemon.battle.engine.ability.AbilityEffect
import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.engine.resolveSwitchInAbility
import com.pokemon.battle.engine.resolveSwitchOutClearing
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Slot

/**
 * Emergency Exit: the holder switches out when HP drops to or below 50% from a hit.
 *
 * Limitation: the engine picks the first available bench Pokemon. Real games let the
 * player pick. A future `ForcedSwitchProvider` on the pipeline would lift this.
 * If the bench is empty, the ability does nothing.
 */
object EmergencyExitEffect : AbilityEffect {
    override val ability = Ability.EMERGENCY_EXIT

    override fun onHpThresholdCrossed(
        state: BattleState,
        slot: Slot,
        previousHp: Int,
        abilities: AbilityRegistry,
    ): List<GameEvent> {
        val holder = state.pokemonFor(slot)
        val threshold = holder.maxHp / 2
        // Only trigger on crossing the threshold (not if already below it).
        if (previousHp <= threshold) return emptyList()
        if (holder.currentHp > threshold) return emptyList()
        if (holder.currentHp <= 0) return emptyList() // fainted — no switch

        val bench = state.benchFor(slot.side)
        val replacementIndex = bench.indexOfFirst { !it.isFainted }
        if (replacementIndex < 0) return emptyList() // no valid replacement

        val events = mutableListOf<GameEvent>()
        events.add(AbilityTriggered(slot, Ability.EMERGENCY_EXIT))
        events.addAll(resolveSwitchOutClearing(state, slot))
        events.add(SwitchOut(slot))
        events.add(SwitchIn(slot, replacementIndex))

        // Compute the post-switch state to resolve switch-in ability
        val stateAfterSwitch = events.fold(state) { s, e -> e.apply(s) }
        events.addAll(resolveSwitchInAbility(stateAfterSwitch, slot, abilities))

        return events
    }
}
