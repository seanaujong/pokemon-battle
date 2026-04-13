package com.pokemon.battle.render

import com.pokemon.battle.engine.*
import com.pokemon.battle.model.*
import com.pokemon.battle.loop.*

fun interface BattleRenderer {
    fun render(event: BattleEvent, stateBefore: BattleState, stateAfter: BattleState): List<String>
}

/** Render a full battle result into text lines, replaying from the initial state. */
fun renderBattle(result: BattleResult, initialState: BattleState, renderer: BattleRenderer = TextRenderer): List<String> {
    val lines = mutableListOf<String>()
    var currentState = initialState

    for (turnResult in result.turnHistory) {
        lines.add("--- Turn ${turnResult.turnNumber} ---")

        for (event in turnResult.events) {
            val stateAfter = event.apply(currentState)
            lines.addAll(renderer.render(event, currentState, stateAfter))
            currentState = stateAfter
        }

        for (event in turnResult.replacementEvents) {
            val stateAfter = event.apply(currentState)
            lines.addAll(renderer.render(event, currentState, stateAfter))
            currentState = stateAfter
        }

        // Increment turn (BattleLoop does this between turns)
        currentState = currentState.copy(turn = currentState.turn + 1)
    }

    when (result.winner) {
        Side.SIDE_1 -> lines.add("\nSide 1 wins!")
        Side.SIDE_2 -> lines.add("\nSide 2 wins!")
        null -> lines.add("\nThe battle ended in a draw!")
    }

    return lines
}
