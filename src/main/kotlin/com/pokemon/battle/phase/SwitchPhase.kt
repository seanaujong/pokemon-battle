package com.pokemon.battle.phase

import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*

/**
 * Resolves voluntary switches before moves execute.
 * Switches are processed in speed order (faster Pokemon switches first),
 * which matters for switch-in triggers like Intimidate.
 *
 * Clearing volatiles and stat stages on switch-out is a game rule (gen-specific),
 * so it lives here in the phase, not in SwitchOut.apply().
 */
class SwitchPhase : Phase {
    override fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent> {
        val events = mutableListOf<BattleEvent>()
        var currentState = state

        val switchingSlots = currentState.allSlots()
            .filter { choices.choiceFor(it) is TurnChoice.Switch }
            .sortedByDescending { GenVSpeedResolver.effectiveSpeed(currentState.pokemonFor(it)) }

        for (slot in switchingSlots) {
            val choice = choices.choiceFor(slot) as TurnChoice.Switch

            // Clear volatiles and stat stages before switch-out (gen-specific rule)
            for (event in clearOnSwitchOut(currentState, slot)) {
                events.add(event)
                currentState = event.apply(currentState)
            }

            val switchOut = SwitchOut(slot)
            events.add(switchOut)
            currentState = switchOut.apply(currentState)

            val switchIn = SwitchIn(slot, choice.benchIndex)
            events.add(switchIn)
            currentState = switchIn.apply(currentState)

            // Switch-in ability triggers (shared with BattleLoop faint replacements)
            for (event in resolveSwitchInAbility(currentState, slot)) {
                events.add(event)
                currentState = event.apply(currentState)
            }
        }

        return events
    }

    /** Emit events to clear volatiles and stat stages before switching out. */
    private fun clearOnSwitchOut(state: BattleState, slot: Slot): List<BattleEvent> {
        val pokemon = state.pokemonFor(slot)
        val events = mutableListOf<BattleEvent>()

        // Clear each volatile
        for (volatile in pokemon.volatiles) {
            events.add(VolatileChanged(slot, volatile, null))
        }

        // Reset stat stages
        for (stat in StatType.entries) {
            val stage = pokemon.statStages.withChange(stat, 0) // read current
            val current = when (stat) {
                StatType.ATTACK -> pokemon.statStages.attack
                StatType.DEFENSE -> pokemon.statStages.defense
                StatType.SPECIAL_ATTACK -> pokemon.statStages.specialAttack
                StatType.SPECIAL_DEFENSE -> pokemon.statStages.specialDefense
                StatType.SPEED -> pokemon.statStages.speed
            }
            if (current != 0) {
                events.add(StatChanged(slot, stat, -current))
            }
        }

        return events
    }
}
