package com.pokemon.battle.ingest.smogon

import kotlinx.serialization.Serializable

/**
 * Consumer-facing summary of one Smogon format at one rating cutoff: the top-N
 * most-used species and their most-picked moves / items / abilities.
 *
 * Committed to `data/smogon/<format>-<rating>-top-sets.json`. This is the "domain"
 * shape — readable, small, trimmed to what play and tests actually consume.
 * See diary 041 Phase 3.
 */
@Serializable
data class SmogonFormatSets(
    val format: String,
    val month: String,
    val ratingCutoff: Int,
    val numberOfBattles: Int,
    val topSpecies: List<SmogonSpeciesSet>,
)

@Serializable
data class SmogonSpeciesSet(
    val name: String,
    val usageRank: Int,
    val rawCount: Int,
    val topAbilities: List<String>,
    val topItems: List<String>,
    val topMoves: List<String>,
)
