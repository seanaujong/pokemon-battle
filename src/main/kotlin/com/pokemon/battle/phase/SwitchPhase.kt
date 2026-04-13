package com.pokemon.battle.phase

import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*

/**
 * Resolves voluntary switches before moves execute.
 * Switches are processed in speed order (faster Pokemon switches first),
 * which matters for switch-in triggers like Intimidate.
 */
class SwitchPhase : Phase {
    override fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent> {
        val events = mutableListOf<BattleEvent>()
        var currentState = state

        val switchingSlots = currentState.allSlots()
            .filter { choices.choiceFor(it) is TurnChoice.Switch }
            .sortedByDescending { currentState.pokemonFor(it).effectiveSpeed() }

        for (slot in switchingSlots) {
            val choice = choices.choiceFor(slot) as TurnChoice.Switch

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
