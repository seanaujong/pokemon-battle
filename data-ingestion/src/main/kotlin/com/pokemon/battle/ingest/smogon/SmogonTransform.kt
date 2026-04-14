package com.pokemon.battle.ingest.smogon

import kotlinx.serialization.json.Json

/**
 * Transforms a projected Smogon chaos file into a [SmogonFormatSets] summary:
 * top-N species by raw usage count, each with their top-K moves, items, and
 * abilities sorted by weight.
 *
 * Pure function; testable without network. See diary 041 Phase 3.
 */
object SmogonTransform {
    private val json = Json { ignoreUnknownKeys = true }

    data class Limits(
        val topSpecies: Int = 30,
        val topMoves: Int = 4,
        val topItems: Int = 4,
        val topAbilities: Int = 2,
    )

    fun transform(
        projectedJson: String,
        month: String,
        ratingCutoff: Int,
        limits: Limits = Limits(),
    ): SmogonFormatSets {
        val chaos = json.decodeFromString(SmogonChaosFile.serializer(), projectedJson)
        val rankedSpecies =
            chaos.data.entries
                .sortedByDescending { it.value.rawCount }
                .take(limits.topSpecies)

        val species =
            rankedSpecies.mapIndexed { index, (name, stats) ->
                SmogonSpeciesSet(
                    name = name,
                    usageRank = index + 1,
                    rawCount = stats.rawCount,
                    topAbilities = stats.abilities.topNByValue(limits.topAbilities),
                    topItems = stats.items.topNByValue(limits.topItems),
                    topMoves = stats.moves.topNByValue(limits.topMoves),
                )
            }

        return SmogonFormatSets(
            format = chaos.info.metagame,
            month = month,
            ratingCutoff = ratingCutoff,
            numberOfBattles = chaos.info.numberOfBattles,
            topSpecies = species,
        )
    }

    private fun Map<String, Double>.topNByValue(n: Int): List<String> = entries.sortedByDescending { it.value }.take(n).map { it.key }
}
