package com.pokemon.battle.ingest.smogon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Smogon monthly "chaos" JSON shape for a (format, rating) pair.
 *
 * Source: `https://www.smogon.com/stats/<YYYY-MM>/chaos/<format>-<rating>.json`.
 * Doubles as the projection contract — the committed `data/raw/smogon/` files
 * are re-serialized versions of these DTOs, containing only the fields we use.
 *
 * Fields we intentionally don't model (Viability Ceiling, Spreads, Teammates,
 * Checks and Counters, Happiness, Teammates) are dropped via `ignoreUnknownKeys`
 * on the deserializer; add them here when a downstream consumer needs them.
 */
@Serializable
data class SmogonChaosFile(
    val info: SmogonInfo,
    val data: Map<String, SmogonPokemonStats>,
)

@Serializable
data class SmogonInfo(
    val metagame: String,
    val cutoff: Double,
    @SerialName("cutoff deviation") val cutoffDeviation: Int,
    @SerialName("team type") val teamType: String? = null,
    @SerialName("number of battles") val numberOfBattles: Int,
)

@Serializable
data class SmogonPokemonStats(
    @SerialName("Raw count") val rawCount: Int,
    @SerialName("Abilities") val abilities: Map<String, Double> = emptyMap(),
    @SerialName("Items") val items: Map<String, Double> = emptyMap(),
    @SerialName("Moves") val moves: Map<String, Double> = emptyMap(),
)
