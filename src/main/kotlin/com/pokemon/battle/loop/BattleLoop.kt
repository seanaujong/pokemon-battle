package com.pokemon.battle.loop

import com.pokemon.battle.engine.*
import com.pokemon.battle.model.*

fun interface ChoiceProvider {
    fun getChoices(state: BattleState): TurnChoices
}

fun interface FaintReplacementProvider {
    fun getReplacement(
        state: BattleState,
        faintedSlot: Slot,
    ): Int
}

data class TurnResult(
    val turnNumber: Int,
    val events: List<BattleEvent>,
    val replacementEvents: List<BattleEvent> = emptyList(),
)

data class BattleResult(
    val winner: Side?,
    val finalState: BattleState,
    val turnHistory: List<TurnResult>,
)

class BattleLoop(
    private val pipeline: TurnPipeline,
    private val choiceProvider: ChoiceProvider,
    private val faintReplacementProvider: FaintReplacementProvider,
    private val maxTurns: Int = 100,
) {
    fun run(initialState: BattleState): BattleResult {
        var state = initialState
        val turnHistory = mutableListOf<TurnResult>()

        while (turnHistory.size < maxTurns) {
            val choices = choiceProvider.getChoices(state)
            val result = pipeline.resolve(state, choices)
            state = result.finalState

            // Increment turn counter
            state = state.copy(turn = state.turn + 1)

            // Handle faint replacements (separate from pipeline events)
            val replacementEvents = mutableListOf<BattleEvent>()
            state = handleFaintReplacements(state, replacementEvents)

            turnHistory.add(TurnResult(state.turn - 1, result.events, replacementEvents))

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
