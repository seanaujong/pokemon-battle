package com.pokemon.battle.phase

import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*

/**
 * Resolves voluntary switches before moves execute.
 * Emits SwitchOut + SwitchIn for each slot that chose to switch.
 */
class SwitchPhase : Phase {
    override fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent> {
        val events = mutableListOf<BattleEvent>()
        var currentState = state

        for (slot in currentState.allSlots()) {
            val choice = choices.choiceFor(slot)
            if (choice !is TurnChoice.Switch) continue

            val switchOut = SwitchOut(slot)
            events.add(switchOut)
            currentState = switchOut.apply(currentState)

            val switchIn = SwitchIn(slot, choice.benchIndex)
            events.add(switchIn)
            currentState = switchIn.apply(currentState)
        }

        return events
    }
}
