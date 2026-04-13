package com.pokemon.battle.ai

import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*
import com.pokemon.battle.loop.*

/**
 * Picks a random move for each slot. Baseline AI for testing.
 *
 * [movePools] maps each slot to the moves available to that slot's Pokemon.
 * [random] is injectable for deterministic tests.
 */
class RandomAI(
    private val movePools: Map<Slot, List<Move>>,
    private val random: java.util.Random = java.util.Random()
) : ChoiceProvider, FaintReplacementProvider {

    override fun getChoices(state: BattleState): TurnChoices {
        val choices = mutableMapOf<Slot, TurnChoice>()

        for (slot in state.allSlots()) {
            val pokemon = state.pokemonFor(slot)
            if (pokemon.isFainted) continue

            val moves = movePools[slot] ?: continue
            if (moves.isEmpty()) continue

            val move = moves[random.nextInt(moves.size)]
            choices[slot] = TurnChoice.UseMove(move)
        }

        return TurnChoices(choices)
    }

    override fun getReplacement(state: BattleState, faintedSlot: Slot): Int = 0
}
