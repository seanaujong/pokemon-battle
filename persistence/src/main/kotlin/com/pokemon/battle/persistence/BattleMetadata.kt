package com.pokemon.battle.persistence

import kotlinx.serialization.Serializable

/**
 * Consumer-level metadata that surrounds a battle. Does **not** live on
 * [com.pokemon.battle.loop.BattleResult] — those fields are pure-mechanics
 * output. Battle identity, timestamps, format tags, and client info are the
 * recording layer's concerns and belong here in `:persistence`. Diary 078
 * spells out the layering rationale.
 *
 * [battleId] is caller-supplied so tests can assert on stable ids; production
 * callers typically pass a fresh UUID via [BattleMetadata.forNewBattle].
 *
 * [startedAtEpochMs] / [endedAtEpochMs] are ms since epoch rather than
 * `java.time.Instant` so the class round-trips cleanly through
 * kotlinx-serialization without platform-specific wrappers — keeps the door
 * open for the KMP refactor flagged in diary 073.
 */
@Serializable
data class BattleMetadata(
    val battleId: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val formatTag: String,
    val protocolVersion: Int? = null,
    val clientInfo: String? = null,
)
