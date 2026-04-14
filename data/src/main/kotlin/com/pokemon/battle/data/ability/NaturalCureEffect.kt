package com.pokemon.battle.data.ability

import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.StatusCleared
import com.pokemon.battle.engine.ability.AbilityEffect
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Slot

/**
 * Natural Cure: clears the holder's non-volatile status condition (burn, poison,
 * paralysis, sleep, freeze) when the holder switches out voluntarily or via a
 * self-switch move (U-turn, Volt Switch). No effect if the holder has no status,
 * and never fires on faint (faint replacement uses a different seam — see
 * [AbilityEffect.onSwitchOut]'s docstring).
 */
object NaturalCureEffect : AbilityEffect {
    override val ability = Ability.NATURAL_CURE

    override fun onSwitchOut(
        state: BattleState,
        slot: Slot,
    ): List<GameEvent> {
        val holder = state.pokemonFor(slot)
        val status = holder.status ?: return emptyList()
        return listOf(
            AbilityTriggered(slot, Ability.NATURAL_CURE),
            StatusCleared(slot, status),
        )
    }
}
