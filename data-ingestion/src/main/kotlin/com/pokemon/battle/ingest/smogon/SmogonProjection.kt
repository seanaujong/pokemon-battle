package com.pokemon.battle.ingest.smogon

import kotlinx.serialization.json.Json

/**
 * Filters a verbatim Smogon chaos JSON down to the fields downstream transforms
 * read. Mirrors the shape established by
 * [com.pokemon.battle.ingest.fetch.PokeApiProjection] (diary 041):
 * deserialize → DTO → re-serialize. The committed `data/raw/smogon/` is definitionally
 * "the Smogon fields this codebase depends on."
 *
 * Drops (via `ignoreUnknownKeys`): Viability Ceiling, Spreads, Teammates, Checks and
 * Counters, Happiness, Tera Types (not yet modeled). Add them to
 * [SmogonPokemonStats] when a consumer needs them; re-project to widen the committed
 * subset.
 */
object SmogonProjection {
    private val reader = Json { ignoreUnknownKeys = true }
    private val writer = Json { prettyPrint = true }

    fun projectChaos(rawJson: String): String {
        val dto = reader.decodeFromString(SmogonChaosFile.serializer(), rawJson)
        return writer.encodeToString(SmogonChaosFile.serializer(), dto)
    }
}
