package com.pokemon.battle.loop

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.InputRequest
import com.pokemon.battle.engine.InputResponse

/**
 * Answers mid-turn prompts emitted by the pipeline (diary 055 Phase 2).
 *
 * When [TurnPipeline][com.pokemon.battle.engine.TurnPipeline] returns a
 * [TurnResolution.NeedInput][com.pokemon.battle.engine.TurnResolution.NeedInput],
 * [BattleLoop] calls [respond] with the paused state and the outstanding request,
 * then resumes the pipeline with the caller's answer.
 *
 * Third peer to [ChoiceProvider] (pre-turn) and [FaintReplacementProvider]
 * (post-KO). Could be unified into one caller-input interface later; kept
 * separate for now so each lifecycle point is named explicitly.
 */
fun interface InputResponder {
    fun respond(
        state: BattleState,
        request: InputRequest,
    ): InputResponse
}
