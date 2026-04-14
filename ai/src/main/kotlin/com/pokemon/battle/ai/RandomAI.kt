package com.pokemon.battle.ai

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.loop.ChoiceProvider
import com.pokemon.battle.loop.FaintReplacementProvider
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.Slot
import kotlin.random.Random

/**
 * Picks a random move for each slot. Baseline AI for testing.
 *
 * [movePools] maps species name to available moves.
 * [random] is injectable for deterministic tests.
 */
class RandomAI(
    private val movePools: Map<String, List<Move>>,
    private val random: Random = Random,
) : ChoiceProvider, FaintReplacementProvider {
    override fun getChoices(state: BattleState): TurnChoices {
        val choices = mutableMapOf<Slot, TurnChoice>()

        for (slot in state.allSlots()) {
            val pokemon = state.pokemonFor(slot)
            if (pokemon.isFainted) continue

            val moves = movePools[pokemon.pokemon.species.name] ?: continue
            if (moves.isEmpty()) continue

            val move = moves.random(random)
            choices[slot] = TurnChoice.UseMove(move)
        }

        return TurnChoices(choices)
    }

    override fun getReplacement(
        state: BattleState,
        faintedSlot: Slot,
    ): Int = 0
}
