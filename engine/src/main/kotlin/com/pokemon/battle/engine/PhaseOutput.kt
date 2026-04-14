package com.pokemon.battle.engine

/**
 * What a [Phase] produces when it runs. Either a completed list of events, or a list
 * of events plus a request for mid-turn input the caller must answer before the
 * pipeline can proceed.
 *
 * See diary 055. Phase 1 (plumbing) always produced `Completed`; Phase 2 introduces
 * `Paused` when a self-switch move needs a mid-turn target.
 */
sealed interface PhaseOutput {
    val events: List<BattleEvent>

    data class Completed(override val events: List<BattleEvent>) : PhaseOutput

    /**
     * The phase emitted [events] up to a pause point and wants the caller to answer
     * [request] before the pipeline can continue. The pipeline translates this into
     * a [TurnPausedForInput] event and halts.
     */
    data class Paused(
        override val events: List<BattleEvent>,
        val request: InputRequest,
    ) : PhaseOutput
}
