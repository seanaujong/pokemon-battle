package com.pokemon.battle.engine

class TurnPipeline(private val phases: List<Phase>) {
    /**
     * Runs every phase to produce a [TurnResolution]. If a phase signals a mid-turn
     * pause via [PhaseOutput.Paused], the pipeline emits a [TurnPausedForInput] event
     * (which records the paused phase index in state) and returns [TurnResolution.NeedInput]
     * with accumulated events stashed in [BattleState.partialTurnEvents].
     *
     * Callers handling pauses must use [resume] with the caller's response.
     */
    fun resolve(
        initialState: BattleState,
        choices: TurnChoices,
    ): TurnResolution {
        check(initialState.pendingInput == null) {
            "Pipeline.resolve called on paused state; use resume() instead."
        }
        var state = initialState
        val events = mutableListOf<BattleEvent>()
        for ((index, phase) in phases.withIndex()) {
            val output = phase.resolve(state, choices)
            for (event in output.events) {
                events.add(event)
                state = event.apply(state)
            }
            if (output is PhaseOutput.Paused) {
                val pauseEvent = TurnPausedForInput(output.request, index)
                events.add(pauseEvent)
                state = pauseEvent.apply(state)
                return TurnResolution.NeedInput(state.copy(partialTurnEvents = events.toList()))
            }
        }
        return TurnResolution.Completed(state.copy(partialTurnEvents = emptyList()), events)
    }

    /**
     * Resumes a paused turn with the caller's [response]. Applies [TurnInputResolved],
     * then re-runs phases starting from [BattleState.pausedPhaseIndex]. The paused phase
     * reads the response from [BattleState.partialTurnEvents] to continue its work.
     */
    fun resume(
        pausedState: BattleState,
        choices: TurnChoices,
        response: InputResponse,
    ): TurnResolution {
        val pausedIndex =
            pausedState.pausedPhaseIndex
                ?: error("Pipeline.resume called on non-paused state")
        val events = pausedState.partialTurnEvents.toMutableList()
        val resolvedEvent = TurnInputResolved(response)
        events.add(resolvedEvent)
        var state = resolvedEvent.apply(pausedState)
        // Make the resolved response visible to the resumed phase via partialTurnEvents.
        state = state.copy(partialTurnEvents = events.toList())

        for (index in pausedIndex until phases.size) {
            val output = phases[index].resolve(state, choices)
            for (event in output.events) {
                events.add(event)
                state = event.apply(state)
            }
            if (output is PhaseOutput.Paused) {
                // Nested or repeated pause. Record and halt again.
                val pauseEvent = TurnPausedForInput(output.request, index)
                events.add(pauseEvent)
                state = pauseEvent.apply(state)
                return TurnResolution.NeedInput(state.copy(partialTurnEvents = events.toList()))
            }
        }
        return TurnResolution.Completed(state.copy(partialTurnEvents = emptyList()), events)
    }

    /**
     * Convenience for callers that don't yet handle mid-turn pauses (tests, the
     * AI-vs-AI demo, the pre-055 CLI). Asserts the turn completed without pausing
     * and returns the [TurnResolution.Completed] directly.
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
