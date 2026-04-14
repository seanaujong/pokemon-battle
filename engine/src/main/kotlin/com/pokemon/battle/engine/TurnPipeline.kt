package com.pokemon.battle.engine

class TurnPipeline(private val phases: List<Phase>) {
    /**
     * Runs every phase to produce a [TurnResolution]. If a phase signals a mid-turn
     * pause via [PhaseOutput.Paused], the pipeline emits a [TurnPausedForInput]
     * event (which records the paused phase index on [PipelineState]) and returns
     * [TurnResolution.NeedInput] with events stashed in [PipelineState.partialTurnEvents].
     *
     * Callers handling pauses must use [resume] with the caller's response.
     */
    fun resolve(
        initialState: BattleState,
        choices: TurnChoices,
    ): TurnResolution {
        var pipeline = PipelineState(battle = initialState)
        val events = mutableListOf<BattleEvent>()
        for ((index, phase) in phases.withIndex()) {
            val output = phase.resolve(pipeline, choices)
            for (event in output.events) {
                events.add(event)
                pipeline = event.applyTo(pipeline)
            }
            if (output is PhaseOutput.Paused) {
                val pauseEvent = TurnPausedForInput(output.request, index)
                events.add(pauseEvent)
                pipeline = pauseEvent.applyTo(pipeline)
                return TurnResolution.NeedInput(pipeline.copy(partialTurnEvents = events.toList()))
            }
        }
        return TurnResolution.Completed(pipeline.battle, events)
    }

    /**
     * Resumes a paused turn with the caller's [response]. Applies [TurnInputResolved],
     * then re-runs phases starting from [PipelineState.pausedPhaseIndex]. The paused
     * phase reads the response from [PipelineState.partialTurnEvents] to continue.
     */
    fun resume(
        paused: PipelineState,
        choices: TurnChoices,
        response: InputResponse,
    ): TurnResolution {
        val pausedIndex =
            paused.pausedPhaseIndex
                ?: error("Pipeline.resume called on non-paused state")
        val events = paused.partialTurnEvents.toMutableList()
        val resolvedEvent = TurnInputResolved(response)
        events.add(resolvedEvent)
        var pipeline = resolvedEvent.applyTo(paused).copy(partialTurnEvents = events.toList())

        for (index in pausedIndex until phases.size) {
            val output = phases[index].resolve(pipeline, choices)
            for (event in output.events) {
                events.add(event)
                pipeline = event.applyTo(pipeline)
            }
            if (output is PhaseOutput.Paused) {
                val pauseEvent = TurnPausedForInput(output.request, index)
                events.add(pauseEvent)
                pipeline = pauseEvent.applyTo(pipeline)
                return TurnResolution.NeedInput(pipeline.copy(partialTurnEvents = events.toList()))
            }
        }
        return TurnResolution.Completed(pipeline.battle, events)
    }

    /**
     * Convenience for callers that don't yet handle mid-turn pauses (tests, the
     * AI-vs-AI demo). Asserts the turn completed without pausing.
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
