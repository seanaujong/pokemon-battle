package com.pokemon.battle.engine

import com.pokemon.battle.model.*

data class MoveOrderResult(
    val order: List<Slot>,
    val leadReason: OrderReason  // why the first slot leads — may not describe the full ordering in multi-slot formats
)

/**
 * Sorts slots by priority bracket (from their chosen move), then by effective speed.
 * Works for any number of slots — singles returns 2, doubles returns 4, etc.
 */
fun resolveMoveOrder(state: BattleState, choices: TurnChoices): MoveOrderResult {
    val slotsWithPriority = state.allSlots().mapNotNull { slot ->
        val choice = choices.choiceFor(slot) as? TurnChoice.UseMove ?: return@mapNotNull null
        val priority = choice.move.priority
        val speed = state.pokemonFor(slot).effectiveSpeed()
        Triple(slot, priority, speed)
    }

    val sorted = slotsWithPriority.sortedWith(
        compareByDescending<Triple<Slot, Int, Double>> { it.second }
            .thenByDescending { it.third }
    )

    val leadReason = when {
        sorted.size < 2 -> OrderReason.SPEED
        sorted[0].second != sorted[1].second -> OrderReason.PRIORITY
        sorted[0].third != sorted[1].third -> OrderReason.SPEED
        else -> OrderReason.SPEED_TIE
    }

    return MoveOrderResult(sorted.map { it.first }, leadReason)
}
