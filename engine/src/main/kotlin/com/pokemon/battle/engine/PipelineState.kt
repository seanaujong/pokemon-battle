package com.pokemon.battle.engine

/**
 * Pipeline-machinery state — what's needed to resume a paused turn. Wraps the game
 * state ([battle]) so callers that only care about the game (renderers, AI scoring,
 * win-condition checks) can read [PipelineState.battle] and ignore resumption
 * bookkeeping. See diary 061.
 *
 * For an unpaused fresh turn, [pendingInput] / [partialTurnEvents] / [pausedPhaseIndex]
 * are all default-empty; the pipeline produces a `Completed` resolution that drops
 * back to a plain [BattleState].
 */
data class PipelineState(
    val battle: BattleState,
    val pendingInput: InputRequest? = null,
    val partialTurnEvents: List<BattleEvent> = emptyList(),
    val pausedPhaseIndex: Int? = null,
)
