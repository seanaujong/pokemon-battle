package com.pokemon.battle.engine

/**
 * Outcome of running [TurnPipeline.resolve]. Either the turn finished, or a phase
 * emitted a mid-turn prompt that the caller must answer before resuming.
 *
 * Today (Phase 1 of diary 055), the pipeline always returns [Completed] — no phase
 * signals a pause. The sealed type is plumbing so Phase 2 can migrate U-turn to
 * mid-turn prompts without changing the `TurnPipeline.resolve` signature again.
 */
sealed interface TurnResolution {
    /** Turn finished. [state] reflects all applied events; [events] is the ordered list. */
    data class Completed(
        val state: BattleState,
        val events: List<BattleEvent>,
    ) : TurnResolution

    /**
     * A phase paused mid-turn. [state] carries the pending prompt (via
     * [BattleState.pendingInput]) and the events emitted so far this turn (via
     * [BattleState.partialTurnEvents]) so the caller can render progress while
     * waiting for input.
     */
    data class NeedInput(
        val state: BattleState,
    ) : TurnResolution
}
