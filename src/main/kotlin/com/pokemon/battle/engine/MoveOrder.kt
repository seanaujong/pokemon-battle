package com.pokemon.battle.engine

import com.pokemon.battle.model.*

data class MoveOrderResult(val order: List<Slot>, val reason: OrderReason)

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
        compareByDescending<Triple<Slot, Int, Double>> { it.second }  // higher priority first
            .thenByDescending { it.third }                             // higher speed first
    )

    val reason = when {
        sorted.size < 2 -> OrderReason.SPEED
        sorted[0].second != sorted[1].second -> OrderReason.PRIORITY
        sorted[0].third != sorted[1].third -> OrderReason.SPEED
        else -> OrderReason.SPEED_TIE // TODO: random tie-break
    }

    return MoveOrderResult(sorted.map { it.first }, reason)
}
