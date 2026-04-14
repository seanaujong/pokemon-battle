package com.pokemon.battle.ai

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.InputRequest
import com.pokemon.battle.engine.InputResponse
import com.pokemon.battle.engine.SwitchTargetRequest
import com.pokemon.battle.engine.SwitchTargetResponse
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.loop.ChoiceProvider
import com.pokemon.battle.loop.FaintReplacementProvider
import com.pokemon.battle.loop.InputResponder
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveEffect
import com.pokemon.battle.model.MoveTarget
import com.pokemon.battle.model.Slot

/**
 * "Set up once, then attack" strategy. Plays a user-targeted stat-boost move
 * (Swords Dance, Nasty Plot, Quiver Dance, Dragon Dance) on turn 1 if one is
 * available and the holder hasn't been boosted yet; falls through to TypeAI's
 * scoring otherwise.
 *
 * Tests whether spending a turn on setup is a win in the 3v3 matrix — diary 085's
 * motivating question.
 *
 * Delegation: the attack case hands off to [TypeAI] rather than duplicating the
 * "pick the strongest move" logic. If TypeAI's scoring changes, HeuristicAI's
 * attack behavior changes with it. Intentional.
 */
class HeuristicAI(
    private val movePools: Map<String, List<Move>>,
) : ChoiceProvider, FaintReplacementProvider, InputResponder {
    private val typeAI = TypeAI(movePools)

    override fun getChoices(state: BattleState): TurnChoices {
        val choices = mutableMapOf<Slot, TurnChoice>()
        val fallback = typeAI.getChoices(state).choices

        for (slot in state.allSlots()) {
            val pokemon = state.pokemonFor(slot)
            if (pokemon.isFainted) continue

            val moves = movePools[pokemon.pokemon.species.name] ?: continue
            val setupMove = pickSetupMove(moves, pokemon.statStages)
            if (setupMove != null) {
                choices[slot] = TurnChoice.UseMove(setupMove)
            } else {
                fallback[slot]?.let { choices[slot] = it }
            }
        }
        return TurnChoices(choices)
    }

    /**
     * Returns a self-targeted stat-boost move if the holder has one available
     * and hasn't been boosted yet; null otherwise. "Boosted" = any positive
     * stat stage on atk / spa / spe — the stats the four canonical setup
     * moves target.
     */
    private fun pickSetupMove(
        moves: List<Move>,
        stages: com.pokemon.battle.model.StatStages,
    ): Move? {
        val alreadyBoosted = stages.attack > 0 || stages.specialAttack > 0 || stages.speed > 0
        if (alreadyBoosted) return null
        return moves.firstOrNull { move ->
            move.target == MoveTarget.SELF &&
                move.effects.any { effect -> isPositiveSelfBoost(effect) }
        }
    }

    private fun isPositiveSelfBoost(effect: MoveEffect): Boolean =
        when (effect) {
            is MoveEffect.StatBoost -> effect.stages > 0
            is MoveEffect.UserStatBoost -> effect.stages > 0
            else -> false
        }

    override fun getReplacement(
        state: BattleState,
        faintedSlot: Slot,
    ): Int = typeAI.getReplacement(state, faintedSlot)

    override fun respond(
        state: BattleState,
        request: InputRequest,
    ): InputResponse =
        when (request) {
            is SwitchTargetRequest -> SwitchTargetResponse(benchIndex = getReplacement(state, request.userSlot))
        }
}
