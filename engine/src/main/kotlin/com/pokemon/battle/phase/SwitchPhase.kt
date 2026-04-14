package com.pokemon.battle.phase

import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.GenVSpeedResolver
import com.pokemon.battle.engine.Phase
import com.pokemon.battle.engine.PhaseOutput
import com.pokemon.battle.engine.SpeedResolver
import com.pokemon.battle.engine.SwitchIn
import com.pokemon.battle.engine.SwitchOut
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.VolatileAdded
import com.pokemon.battle.engine.resolveHazardsOnSwitchIn
import com.pokemon.battle.engine.resolveSwitchInAbility
import com.pokemon.battle.engine.resolveSwitchOutClearing
import com.pokemon.battle.model.Volatile

/**
 * Resolves voluntary switches before moves execute.
 * Switches are processed in speed order (faster Pokemon switches first),
 * which matters for switch-in triggers like Intimidate.
 *
 * Clearing volatiles and stat stages on switch-out is a game rule (gen-specific),
 * so it lives here in the phase, not in SwitchOut.apply().
 */
class SwitchPhase(
    private val speedResolver: SpeedResolver = GenVSpeedResolver,
) : Phase {
    override fun resolve(
        state: BattleState,
        choices: TurnChoices,
    ): PhaseOutput {
        val events = mutableListOf<BattleEvent>()
        var currentState = state

        val switchingSlots =
            currentState.allSlots()
                .filter { choices.choiceFor(it) is TurnChoice.Switch }
                .sortedByDescending { speedResolver.effectiveSpeed(currentState.pokemonFor(it), it, currentState) }

        for (slot in switchingSlots) {
            val choice = choices.choiceFor(slot) as TurnChoice.Switch

            // Clear volatiles and stat stages before switch-out (gen-specific rule)
            for (event in resolveSwitchOutClearing(currentState, slot)) {
                events.add(event)
                currentState = event.apply(currentState)
            }

            val switchOut = SwitchOut(slot)
            events.add(switchOut)
            currentState = switchOut.apply(currentState)

            val switchIn = SwitchIn(slot, choice.benchIndex)
            events.add(switchIn)
            currentState = switchIn.apply(currentState)

            // Mark the new Pokemon as just switched-in (gate for Fake Out, First Impression).
            val justSwitchedIn = VolatileAdded(slot, Volatile.JustSwitchedIn)
            events.add(justSwitchedIn)
            currentState = justSwitchedIn.apply(currentState)

            // Switch-in ability triggers (shared with BattleLoop faint replacements)
            for (event in resolveSwitchInAbility(currentState, slot)) {
                events.add(event)
                currentState = event.apply(currentState)
            }

            // Entry hazards fire after abilities
            for (event in resolveHazardsOnSwitchIn(currentState, slot)) {
                events.add(event)
                currentState = event.apply(currentState)
            }
        }

        return PhaseOutput.Completed(events)
    }
}
