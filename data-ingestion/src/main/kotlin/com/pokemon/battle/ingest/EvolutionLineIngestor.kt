package com.pokemon.battle.ingest

import com.pokemon.battle.data.EvolutionLineJson
import com.pokemon.battle.ingest.fetch.PokeApiChainLink
import com.pokemon.battle.ingest.fetch.PokeApiClient
import com.pokemon.battle.ingest.fetch.PokeApiEvolutionChain
import com.pokemon.battle.ingest.fetch.PokeApiSpecies
import com.pokemon.battle.ingest.transform.EvolutionLineTransform
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the evolution line a species belongs to — at *any* stage — into a
 * self-contained [EvolutionLineJson] bundle, and persists bundles as JSON. Fetching is
 * delegated to [PokeApiClient] (which caches under `.cache/pokeapi`); shaping is
 * [EvolutionLineTransform]'s pure work; the on-disk format mirrors
 * [com.pokemon.battle.data.EvolutionLineDex]'s directory reader.
 *
 * The bundle is keyed by the chain's *root* species, so `buildBundle("drizzile")` yields
 * the Sobble line. Both callers reuse this: the batch [com.pokemon.battle.ingest.cli]
 * entrypoint folds it over the curated targets, and the CLI advisor calls it on a miss.
 */
object EvolutionLineIngestor {
    private const val SPECIES_ENDPOINT = "pokemon-species"
    private const val CHAIN_ENDPOINT = "evolution-chain"
    private const val POKEMON_ENDPOINT = "pokemon"
    private val prettyJson = Json { prettyPrint = true }

    /** Writes [bundle] as pretty JSON to `<directory>/<base>.json`, creating dirs. Returns the path. */
    fun writeBundle(
        bundle: EvolutionLineJson,
        directory: Path,
    ): Path {
        Files.createDirectories(directory)
        val path = directory.resolve("${bundle.base}.json")
        Files.writeString(path, prettyJson.encodeToString(EvolutionLineJson.serializer(), bundle))
        return path
    }

    fun buildBundle(
        slug: String,
        client: PokeApiClient = PokeApiClient(),
        reader: Json = Json { ignoreUnknownKeys = true },
    ): EvolutionLineJson {
        val speciesRaw = client.fetch(SPECIES_ENDPOINT, slug)
        val chainUrl = reader.decodeFromString(PokeApiSpecies.serializer(), speciesRaw).evolutionChain.url
        val chainId = chainUrl.trimEnd('/').substringAfterLast('/')
        val chainRaw = client.fetch(CHAIN_ENDPOINT, chainId)
        val chain = reader.decodeFromString(PokeApiEvolutionChain.serializer(), chainRaw)

        val base = chain.chain.species.name
        val stages = collectSpecies(chain.chain)
        val learnsetRawBySpecies = stages.associateWith { client.fetch(POKEMON_ENDPOINT, it) }
        return EvolutionLineTransform.transform(base, chainRaw, learnsetRawBySpecies)
    }

    private fun collectSpecies(root: PokeApiChainLink): List<String> {
        val species = mutableListOf<String>()

        fun walk(node: PokeApiChainLink) {
            species += node.species.name
            node.evolvesTo.forEach { walk(it) }
        }
        walk(root)
        return species
    }
}
