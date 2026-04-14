package com.pokemon.battle.engine

import com.pokemon.battle.model.OrderReason
import com.pokemon.battle.model.Slot

internal data class MoveOrderResult(
    val order: List<Slot>,
    val leadReason: OrderReason,
)

/**
 * Sorts slots by priority bracket (from their chosen move), then by effective speed.
 * Works for any number of slots — singles returns 2, doubles returns 4, etc.
 */
internal fun resolveMoveOrder(
    state: BattleState,
    choices: TurnChoices,
    speedResolver: SpeedResolver = GenVSpeedResolver,
): MoveOrderResult {
    val slotsWithPriority =
        state.allSlots().mapNotNull { slot ->
            val choice = choices.choiceFor(slot) as? TurnChoice.UseMove ?: return@mapNotNull null
            val priority = choice.move.priority
            val speed = speedResolver.effectiveSpeed(state.pokemonFor(slot), slot, state)
            Triple(slot, priority, speed)
        }

    // Trick Room inverts the speed-tiebreak order within each priority bracket.
    // Priority brackets still resolve normally (higher first).
    val trickRoom = state.field.trickRoomTurnsRemaining > 0
    val sorted =
        slotsWithPriority.sortedWith(
            compareByDescending<Triple<Slot, Int, Double>> { it.second }
                .thenComparator { a, b ->
                    if (trickRoom) a.third.compareTo(b.third) else b.third.compareTo(a.third)
                },
        )

    val leadReason =
        when {
            sorted.size < 2 -> OrderReason.SPEED
            sorted[0].second != sorted[1].second -> OrderReason.PRIORITY
            sorted[0].third != sorted[1].third -> OrderReason.SPEED
            else -> OrderReason.SPEED_TIE
        }

    return MoveOrderResult(sorted.map { it.first }, leadReason)
}
