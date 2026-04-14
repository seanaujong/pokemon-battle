package com.pokemon.battle.engine

import kotlinx.serialization.Serializable

/**
 * Pipeline paused mid-turn waiting for caller input. As a [ControlEvent], this
 * mutates [PipelineState] (not [BattleState]) — sets [PipelineState.pendingInput]
 * so the pipeline halts and downstream consumers see a pause is active. Diary 055.
 */
@Serializable
data class TurnPausedForInput(
    val request: InputRequest,
    val atPhaseIndex: Int,
) : ControlEvent {
    override fun apply(state: PipelineState): PipelineState =
        state.copy(
            pendingInput = request,
            pausedPhaseIndex = atPhaseIndex,
        )
}

/**
 * Caller answered the pending prompt. Clears [PipelineState.pendingInput] and the
 * paused-phase index. The response itself is preserved in the event log, which is
 * what a replay needs.
 *
 * The phase that paused re-runs on resume and reads this event from the turn's
 * partial event list to recover the response value.
 */
@Serializable
data class TurnInputResolved(
    val response: InputResponse,
) : ControlEvent {
    override fun apply(state: PipelineState): PipelineState =
        state.copy(
            pendingInput = null,
            pausedPhaseIndex = null,
        )
}
