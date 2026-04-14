package com.pokemon.battle.engine

/**
 * Outcome of running [TurnPipeline.resolve]. Either the turn finished, or a phase
 * emitted a mid-turn prompt that the caller must answer before resuming.
 *
 * Diary 061: [Completed] carries pure [BattleState] (game only — there's nothing
 * to resume); [NeedInput] carries [PipelineState] (game state plus the pending
 * input and partial events the caller needs to render and respond).
 */
sealed interface TurnResolution {
    /** Turn finished. [state] reflects all applied events; [events] is the ordered list. */
    data class Completed(
        val state: BattleState,
        val events: List<BattleEvent>,
    ) : TurnResolution

    /**
     * A phase paused mid-turn. [state] carries the game snapshot, the pending
     * prompt ([PipelineState.pendingInput]), the partial event list
     * ([PipelineState.partialTurnEvents]) for rendering progress, and the paused
     * phase index ([PipelineState.pausedPhaseIndex]) for resume.
     */
    data class NeedInput(
        val state: PipelineState,
    ) : TurnResolution
}
