package com.pokemon.battle.persistence

import com.pokemon.battle.engine.serialization.toJson
import com.pokemon.battle.loop.BattleResult

/**
 * Records a completed battle for later analysis / replay. Consumers (`:cli` batch
 * runner, future `:server` recording) accept an optional [BattleRecorder] at
 * construction; the default no-op implementation means "don't record" without
 * forcing callers to make a choice.
 *
 * Shape A from diary 078: post-battle write. Per-turn streaming (Shape B) or
 * external sinks (Shape C) are later additions that implement this same
 * interface.
 */
fun interface BattleRecorder {
    fun record(
        result: BattleResult,
        metadata: BattleMetadata,
    )

    companion object {
        /** No-op recorder — the default when the caller hasn't wired a real one. */
        val NoOp: BattleRecorder = BattleRecorder { _, _ -> }
    }
}

/**
 * Converts a [BattleResult] + [BattleMetadata] pair into the on-disk
 * [PersistedBattle] shape. Pure function; does no I/O.
 */
fun toPersisted(
    result: BattleResult,
    metadata: BattleMetadata,
): PersistedBattle =
    PersistedBattle(
        metadata = metadata,
        winner = result.winner,
        turns =
            result.turnHistory.map { turn ->
                PersistedTurn(
                    turnNumber = turn.turnNumber,
                    events = turn.events.map { it.toJson() },
                    replacementEvents = turn.replacementEvents.map { it.toJson() },
                )
            },
    )
