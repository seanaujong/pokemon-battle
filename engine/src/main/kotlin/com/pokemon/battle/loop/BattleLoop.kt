package com.pokemon.battle.loop

import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.SwitchIn
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.TurnResolution
import com.pokemon.battle.engine.resolveSwitchInAbility
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Slot

fun interface ChoiceProvider {
    fun getChoices(state: BattleState): TurnChoices
}

fun interface FaintReplacementProvider {
    fun getReplacement(
        state: BattleState,
        faintedSlot: Slot,
    ): Int
}

data class TurnRecord(
    val turnNumber: Int,
    val events: List<BattleEvent>,
    val replacementEvents: List<BattleEvent> = emptyList(),
)

data class BattleResult(
    val winner: Side?,
    val finalState: BattleState,
    val turnHistory: List<TurnRecord>,
)

class BattleLoop(
    private val pipeline: TurnPipeline,
    private val choiceProvider: ChoiceProvider,
    private val faintReplacementProvider: FaintReplacementProvider,
    private val inputResponder: InputResponder? = null,
    private val maxTurns: Int = 100,
) {
    fun run(initialState: BattleState): BattleResult {
        var state = initialState
        val turnHistory = mutableListOf<TurnRecord>()

        while (turnHistory.size < maxTurns) {
            val choices = choiceProvider.getChoices(state)
            val completed = resolveTurnWithPauses(state, choices)
            state = completed.state

            // Increment turn counter
            state = state.copy(turn = state.turn + 1)

            // Handle faint replacements (separate from pipeline events)
            val replacementEvents = mutableListOf<BattleEvent>()
            state = handleFaintReplacements(state, replacementEvents)

            turnHistory.add(TurnRecord(state.turn - 1, completed.events, replacementEvents))

            // Check win condition
            val side1Defeated = state.isDefeated(Side.SIDE_1)
            val side2Defeated = state.isDefeated(Side.SIDE_2)

            if (side1Defeated && side2Defeated) {
                return BattleResult(winner = null, finalState = state, turnHistory = turnHistory)
            }
            if (side1Defeated) {
                return BattleResult(winner = Side.SIDE_2, finalState = state, turnHistory = turnHistory)
            }
            if (side2Defeated) {
                return BattleResult(winner = Side.SIDE_1, finalState = state, turnHistory = turnHistory)
            }
        }

        return BattleResult(winner = null, finalState = state, turnHistory = turnHistory)
    }

    /**
     * Drive a turn through any mid-turn pauses to completion. Each [TurnResolution.NeedInput]
     * consults [inputResponder]; missing responder with a paused pipeline is an error
     * (the caller wired themselves into a dead end).
     */
    private fun resolveTurnWithPauses(
        state: BattleState,
        choices: TurnChoices,
    ): TurnResolution.Completed {
        var resolution: TurnResolution = pipeline.resolve(state, choices)
        while (resolution is TurnResolution.NeedInput) {
            val responder =
                inputResponder
                    ?: error(
                        "Pipeline paused for mid-turn input but no InputResponder was wired. " +
                            "Either supply one or ensure callers pre-answer (e.g. TurnChoice.switchTo).",
                    )
            val request =
                resolution.state.pendingInput
                    ?: error("TurnResolution.NeedInput without pendingInput on state — engine bug")
            val response = responder.respond(resolution.state, request)
            resolution = pipeline.resume(resolution.state, choices, response)
        }
        return resolution as TurnResolution.Completed
    }

    private fun handleFaintReplacements(
        state: BattleState,
        events: MutableList<BattleEvent>,
    ): BattleState {
        var currentState = state

        for (slot in currentState.allSlots()) {
            val pokemon = currentState.pokemonFor(slot)
            if (!pokemon.isFainted) continue

            val bench = currentState.benchFor(slot.side)
            if (bench.isEmpty()) continue

            val benchIndex = faintReplacementProvider.getReplacement(currentState, slot)
            val switchIn = SwitchIn(slot, benchIndex)
            events.add(switchIn)
            currentState = switchIn.apply(currentState)

            // Switch-in ability triggers for the replacement
            for (abilityEvent in resolveSwitchInAbility(currentState, slot)) {
                events.add(abilityEvent)
                currentState = abilityEvent.apply(currentState)
            }
        }

        return currentState
    }
}
