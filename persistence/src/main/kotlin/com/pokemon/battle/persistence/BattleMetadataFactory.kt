package com.pokemon.battle.persistence

import java.time.Clock
import java.util.UUID

/**
 * Convenience factories for [BattleMetadata]. Kept in `:persistence` (not
 * `:engine`) so the engine stays ignorant of wall-clock time and UUIDs —
 * consistent with diary 078's layering contract.
 */
object BattleMetadataFactory {
    /**
     * Fresh metadata at battle start. Caller calls [withEnded] at battle end
     * to stamp `endedAtEpochMs`; keeping the two-step construction explicit
     * avoids pretending the engine records time.
     */
    fun forNewBattle(
        formatTag: String,
        clock: Clock = Clock.systemUTC(),
        protocolVersion: Int? = null,
        clientInfo: String? = null,
    ): BattleMetadata {
        val now = clock.millis()
        // endedAtEpochMs is seeded to `now`; the caller updates it via withEnded at battle end.
        return BattleMetadata(
            battleId = UUID.randomUUID().toString(),
            startedAtEpochMs = now,
            endedAtEpochMs = now,
            formatTag = formatTag,
            protocolVersion = protocolVersion,
            clientInfo = clientInfo,
        )
    }
}

fun BattleMetadata.withEnded(clock: Clock = Clock.systemUTC()): BattleMetadata = copy(endedAtEpochMs = clock.millis())
