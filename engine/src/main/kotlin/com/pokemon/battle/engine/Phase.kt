package com.pokemon.battle.engine

fun interface Phase {
    /**
     * A phase sees the full [PipelineState] (both game and pipeline bookkeeping) so
     * it can consult [PipelineState.partialTurnEvents] on resume. Phases emit
     * [GameEvent]s (which update [PipelineState.battle]) and may return a
     * [PhaseOutput.Paused] carrying an [InputRequest] to halt the pipeline.
     */
    fun resolve(
        state: PipelineState,
        choices: TurnChoices,
    ): PhaseOutput
}
