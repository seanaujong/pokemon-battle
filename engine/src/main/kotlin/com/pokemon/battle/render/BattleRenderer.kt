package com.pokemon.battle.render

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.loop.BattleResult
import com.pokemon.battle.model.Side

fun interface BattleRenderer {
    fun render(
        event: GameEvent,
        stateBefore: BattleState,
        stateAfter: BattleState,
    ): List<String>
}

/**
 * Render a full battle result into text lines, replaying from the initial state.
 * Filters to [GameEvent]s — pipeline control events (pause/resume from diary 055)
 * are not part of the game's narrative. See diary 061.
 */
fun renderBattle(
    result: BattleResult,
    initialState: BattleState,
    renderer: BattleRenderer = TextRenderer,
): List<String> {
    val lines = mutableListOf<String>()
    var currentState = initialState

    for (turnResult in result.turnHistory) {
        lines.add("--- Turn ${turnResult.turnNumber} ---")

        for (event in turnResult.events.filterIsInstance<GameEvent>()) {
            val stateAfter = event.apply(currentState)
            lines.addAll(renderer.render(event, currentState, stateAfter))
            currentState = stateAfter
        }

        for (event in turnResult.replacementEvents.filterIsInstance<GameEvent>()) {
            val stateAfter = event.apply(currentState)
            lines.addAll(renderer.render(event, currentState, stateAfter))
            currentState = stateAfter
        }
    }

    when (result.winner) {
        Side.SIDE_1 -> lines.add("\nSide 1 wins!")
        Side.SIDE_2 -> lines.add("\nSide 2 wins!")
        null -> lines.add("\nThe battle ended in a draw!")
    }

    return lines
}
