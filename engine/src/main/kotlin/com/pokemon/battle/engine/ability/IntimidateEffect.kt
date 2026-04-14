package com.pokemon.battle.engine.ability

import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatType

/** Intimidate: drops each opponent's Attack by one stage on switch-in. */
object IntimidateEffect : AbilityEffect {
    override val ability = Ability.INTIMIDATE

    override fun onSwitchIn(
        state: BattleState,
        slot: Slot,
    ): List<GameEvent> {
        val events = mutableListOf<GameEvent>(AbilityTriggered(slot, Ability.INTIMIDATE))
        for (opponentSlot in state.opponentSlots(slot)) {
            val opponent = state.pokemonFor(opponentSlot)
            if (!opponent.isFainted) {
                events.add(StatChanged(opponentSlot, StatType.ATTACK, -1))
            }
        }
        return events
    }
}
