package com.pokemon.battle.ingest.fetch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PokeAPI DTOs for the learnset + evolution endpoints, reading only the fields the
 * evolution-delay ingestion needs. Like [PokeApiPokemon], these double as the
 * projection contract — `ignoreUnknownKeys` drops everything else.
 *
 * Note: the learnset view reads `/pokemon/{name}` from the *same* cache the species
 * ingestion populates (`.cache/pokeapi/pokemon/`), so already-fetched species cost no
 * network round-trip — only the `moves[]` field, which the species projection strips.
 */
@Serializable
data class PokeApiPokemonLearnset(
    val name: String,
    val moves: List<PokeApiMove> = emptyList(),
)

@Serializable
data class PokeApiMove(
    val move: NamedResource,
    @SerialName("version_group_details") val versionGroupDetails: List<PokeApiMoveVersionDetail>,
)

@Serializable
data class PokeApiMoveVersionDetail(
    @SerialName("level_learned_at") val levelLearnedAt: Int,
    @SerialName("move_learn_method") val moveLearnMethod: NamedResource,
    @SerialName("version_group") val versionGroup: NamedResource,
)

/** `/pokemon-species/{name}` — only the pointer to the evolution chain. */
@Serializable
data class PokeApiSpecies(
    @SerialName("evolution_chain") val evolutionChain: EvolutionChainRef,
)

@Serializable
data class EvolutionChainRef(val url: String)

/** `/evolution-chain/{id}` — the recursive chain tree. */
@Serializable
data class PokeApiEvolutionChain(
    val chain: PokeApiChainLink,
)

@Serializable
data class PokeApiChainLink(
    val species: NamedResource,
    @SerialName("evolution_details") val evolutionDetails: List<PokeApiEvolutionDetail> = emptyList(),
    @SerialName("evolves_to") val evolvesTo: List<PokeApiChainLink> = emptyList(),
)

/**
 * How a [PokeApiChainLink] evolves *from its parent* — so a node's own details
 * describe the edge into it (the chain root's details are empty).
 */
@Serializable
data class PokeApiEvolutionDetail(
    val trigger: NamedResource,
    @SerialName("min_level") val minLevel: Int? = null,
    val item: NamedResource? = null,
)
