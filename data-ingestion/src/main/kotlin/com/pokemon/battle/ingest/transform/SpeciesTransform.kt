package com.pokemon.battle.ingest.transform

import com.pokemon.battle.data.SpeciesJson
import com.pokemon.battle.ingest.fetch.PokeApiPokemon
import kotlinx.serialization.json.Json

/**
 * Transforms a projected PokeAPI `/pokemon/{id}` response into our on-disk [SpeciesJson].
 * Pure; testable without network.
 */
object SpeciesTransform {
    private val json = Json { ignoreUnknownKeys = true }

    fun transform(rawJson: String): SpeciesJson {
        val raw = json.decodeFromString(PokeApiPokemon.serializer(), rawJson)
        val stats = raw.stats.associate { it.stat.name to it.baseStat }
        return SpeciesJson(
            name = normalizeName(raw.name),
            types = raw.types.sortedBy { it.slot }.map { it.type.name.uppercase() },
            baseHp = stats.require("hp", raw.name),
            baseAttack = stats.require("attack", raw.name),
            baseDefense = stats.require("defense", raw.name),
            baseSpecialAttack = stats.require("special-attack", raw.name),
            baseSpecialDefense = stats.require("special-defense", raw.name),
            baseSpeed = stats.require("speed", raw.name),
        )
    }

    private fun Map<String, Int>.require(
        key: String,
        speciesName: String,
    ): Int = this[key] ?: error("Missing stat '$key' in PokeAPI response for '$speciesName'")

    /**
     * PokeAPI uses lowercase-hyphen slugs (`great-tusk`, `iron-valiant`); the engine
     * uses display-cased names (`Great Tusk`, `Iron Valiant`) since `Species.name` is
     * rendered in event text ("Charizard used Flamethrower!"). Hyphens become spaces;
     * each word title-cased.
     */
    private fun normalizeName(slug: String): String =
        slug
            .split('-')
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercaseChar() }
            }
}
