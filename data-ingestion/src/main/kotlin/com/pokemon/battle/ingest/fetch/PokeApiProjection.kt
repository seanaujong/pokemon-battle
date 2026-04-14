package com.pokemon.battle.ingest.fetch

import kotlinx.serialization.json.Json

/**
 * Filters a full verbatim PokeAPI response down to the fields our DTOs declare.
 *
 * The projection is definitionally "whatever fields [PokeApiPokemon] and friends
 * expose" — deserializing drops everything else, re-serializing produces a JSON
 * document containing only what downstream transforms read. Add a field to a DTO
 * and re-projection widens the committed file automatically.
 */
object PokeApiProjection {
    private val reader = Json { ignoreUnknownKeys = true }
    private val writer = Json { prettyPrint = true }

    fun projectPokemon(rawJson: String): String {
        val dto = reader.decodeFromString(PokeApiPokemon.serializer(), rawJson)
        return writer.encodeToString(PokeApiPokemon.serializer(), dto)
    }
}
