package com.pokemon.battle.engine

import kotlinx.serialization.Serializable

/**
 * The engine paused mid-turn waiting for caller input. Applying this event sets
 * [BattleState.pendingInput] so downstream logic (pipeline, caller) can see a pause
 * is active. The pipeline halts after applying and returns [TurnResolution.NeedInput].
 *
 * Diary 055: replay from events alone still reconstructs every intermediate state.
 */
@Serializable
data class TurnPausedForInput(
    val request: InputRequest,
    val atPhaseIndex: Int,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState =
        state.copy(
            pendingInput = request,
            pausedPhaseIndex = atPhaseIndex,
        )
}

/**
 * Caller answered the pending prompt. Clears [BattleState.pendingInput] and the
 * paused-phase index. The response itself is preserved in the event log, which is
 * what a replay needs.
 *
 * The phase that paused re-runs on resume and reads this event from the turn's
 * partial event list to recover the response value.
 */
@Serializable
data class TurnInputResolved(
    val response: InputResponse,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState =
        state.copy(
            pendingInput = null,
            pausedPhaseIndex = null,
        )
}
