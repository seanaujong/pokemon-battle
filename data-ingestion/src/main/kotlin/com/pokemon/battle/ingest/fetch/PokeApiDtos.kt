package com.pokemon.battle.ingest.fetch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PokeAPI-shaped DTOs — just the fields any transform actually reads.
 *
 * These DTOs double as the *projection contract*: [PokeApiProjection] deserializes
 * a full PokeAPI response into these classes and re-serializes, which filters the
 * JSON down to exactly these fields. The committed `data/raw/pokeapi/` is therefore
 * definitionally "the PokeAPI fields this codebase depends on."
 */
@Serializable
data class PokeApiPokemon(
    val name: String,
    val types: List<TypeSlot>,
    val stats: List<StatEntry>,
)

@Serializable
data class TypeSlot(val slot: Int, val type: NamedResource)

@Serializable
data class StatEntry(
    @SerialName("base_stat") val baseStat: Int,
    val stat: NamedResource,
)

@Serializable
data class NamedResource(val name: String)
