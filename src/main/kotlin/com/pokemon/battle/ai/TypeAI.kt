package com.pokemon.battle.ai

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.loop.ChoiceProvider
import com.pokemon.battle.loop.FaintReplacementProvider
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveTarget
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.typeEffectiveness

/**
 * Picks the move with the best type effectiveness against the opponent.
 * Scores each move by: type effectiveness * STAB * power.
 * For doubles, evaluates all (move, target) combinations.
 *
 * [movePools] maps species name to available moves.
 * Falls back to the first available move if no damaging moves exist.
 */
class TypeAI(
    private val movePools: Map<String, List<Move>>,
) : ChoiceProvider, FaintReplacementProvider {
    override fun getChoices(state: BattleState): TurnChoices {
        val choices = mutableMapOf<Slot, TurnChoice>()

        for (slot in state.allSlots()) {
            val pokemon = state.pokemonFor(slot)
            if (pokemon.isFainted) continue

            val moves = movePools[pokemon.pokemon.species.name] ?: continue
            if (moves.isEmpty()) continue

            val choice = pickBestMove(state, slot, pokemon, moves)
            choices[slot] = choice
        }

        return TurnChoices(choices)
    }

    @Suppress("NestedBlockDepth") // Nested loops for move × target scoring
    private fun pickBestMove(
        state: BattleState,
        slot: Slot,
        pokemon: PokemonState,
        moves: List<Move>,
    ): TurnChoice {
        val opponents =
            state.opponentSlots(slot)
                .filter { !state.pokemonFor(it).isFainted }

        if (opponents.isEmpty()) {
            return TurnChoice.UseMove(moves.first())
        }

        var bestScore = -1.0
        var bestMove = moves.first()
        var bestTarget: Slot? = null

        for (move in moves) {
            if (move.power == 0) continue

            val targets =
                when (move.target) {
                    MoveTarget.ONE_OPPONENT -> opponents
                    MoveTarget.ALL_OPPONENTS -> listOf(opponents.first())
                    MoveTarget.ALL_OTHER -> listOf(opponents.first())
                    MoveTarget.SELF -> continue
                }

            for (target in targets) {
                val defender = state.pokemonFor(target)
                val effectiveness = typeEffectiveness(move.type, defender.effectiveTypes)
                val stab = if (move.type in pokemon.effectiveTypes) 1.5 else 1.0
                val score = effectiveness * stab * move.power

                if (score > bestScore) {
                    bestScore = score
                    bestMove = move
                    bestTarget = if (move.target == MoveTarget.ONE_OPPONENT) target else null
                }
            }
        }

        return TurnChoice.UseMove(bestMove, targetSlot = bestTarget)
    }

    override fun getReplacement(
        state: BattleState,
        faintedSlot: Slot,
    ): Int {
        val bench = state.benchFor(faintedSlot.side)
        val opponents =
            state.opponentSlots(faintedSlot)
                .filter { !state.pokemonFor(it).isFainted }

        if (opponents.isEmpty() || bench.isEmpty()) return 0

        val opponentTypes = state.pokemonFor(opponents.first()).effectiveTypes

        return bench.indices.maxByOrNull { i ->
            val benchTypes = bench[i].effectiveTypes
            benchTypes.sumOf { atkType ->
                typeEffectiveness(atkType, opponentTypes)
            } / benchTypes.size
        } ?: 0
    }
}
