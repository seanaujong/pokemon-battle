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
import com.pokemon.battle.model.GimmickKind
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
            val baseChoice =
                if (setupMove != null) TurnChoice.UseMove(setupMove) else fallback[slot] as? TurnChoice.UseMove
            if (baseChoice != null) {
                choices[slot] = maybeRequestTera(state, slot, baseChoice)
            } else {
                fallback[slot]?.let { choices[slot] = it }
            }
        }
        return TurnChoices(choices)
    }

    /**
     * Opt into Terastallization when the holder's tera type matches the move about to
     * be used — that's the turn the bonus is most impactful. Gated by:
     * - holder has a [com.pokemon.battle.model.Pokemon.teraType] set,
     * - holder hasn't already Terastallized,
     * - the ruleset permits a Tera activation on this side right now,
     * - the move's type equals the tera type (Tera STAB is maximized when the original
     *   and tera types both match, but picking by tera-type match alone is the simpler
     *   heuristic that produces measurable signal).
     */
    private fun maybeRequestTera(
        state: BattleState,
        slot: Slot,
        choice: TurnChoice.UseMove,
    ): TurnChoice.UseMove {
        val holder = state.pokemonFor(slot)
        val teraType = holder.pokemon.teraType ?: return choice
        if (holder.terastallized) return choice
        if (!state.canUseGimmick(GimmickKind.TERA, slot.side)) return choice
        if (choice.move.type != teraType) return choice
        return choice.copy(terastallize = true)
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
