package com.pokemon.battle.engine

class TurnPipeline(private val phases: List<Phase>) {
    /**
     * Runs every phase to produce a [TurnResolution]. Today (Phase 1 of diary 055),
     * no phase emits a pause, so this always returns [TurnResolution.Completed].
     * The sealed return type is plumbing for Phase 2.
     */
    fun resolve(
        initialState: BattleState,
        choices: TurnChoices,
    ): TurnResolution {
        var state = initialState
        val events = mutableListOf<BattleEvent>()
        for (phase in phases) {
            val newEvents = phase.resolve(state, choices)
            events.addAll(newEvents)
            for (event in newEvents) {
                state = event.apply(state)
            }
        }
        return TurnResolution.Completed(state, events)
    }

    /**
     * Convenience for callers that don't yet handle mid-turn pauses (tests, the
     * AI-vs-AI demo, the pre-055 CLI). Asserts the turn completed without pausing
     * and returns the [TurnResolution.Completed] directly.
     *
     * BattleLoop uses [resolve] directly so that when Phase 2 lands it can respond
     * to `NeedInput` without a plumbing change.
     */
    fun resolveToCompletion(
        initialState: BattleState,
        choices: TurnChoices,
    ): TurnResolution.Completed {
        val result = resolve(initialState, choices)
        check(result is TurnResolution.Completed) {
            "Pipeline paused mid-turn; caller does not handle pauses."
        }
        return result
    }
}
