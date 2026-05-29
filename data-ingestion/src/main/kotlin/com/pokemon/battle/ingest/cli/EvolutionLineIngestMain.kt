package com.pokemon.battle.ingest.cli

import com.pokemon.battle.data.EvolutionLineJson
import com.pokemon.battle.ingest.fetch.PokeApiChainLink
import com.pokemon.battle.ingest.fetch.PokeApiClient
import com.pokemon.battle.ingest.fetch.PokeApiEvolutionChain
import com.pokemon.battle.ingest.fetch.PokeApiSpecies
import com.pokemon.battle.ingest.transform.EvolutionLineTransform
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines

private const val SPECIES_ENDPOINT = "pokemon-species"
private const val CHAIN_ENDPOINT = "evolution-chain"
private const val POKEMON_ENDPOINT = "pokemon"
private val TARGETS_FILE: Path = Path.of("targets/evolution-lines.txt")
private val CACHE_ROOT: Path = Path.of(".cache/pokeapi")
private val OUTPUT_DIR: Path = Path.of("data/src/main/resources/dex/evolution-lines")

/**
 * Ingests evolution-line bundles for the delay advisor. For each base species in
 * [TARGETS_FILE]: resolve its evolution chain, then pull every stage's learnset
 * (reusing the shared `.cache/pokeapi/pokemon/` cache), and write a self-contained
 * bundle + an `index.txt` manifest. Mirrors [main] in IngestMain.
 */
fun main() {
    val targets =
        TARGETS_FILE
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    val client = PokeApiClient(cacheRoot = CACHE_ROOT)
    val reader = Json { ignoreUnknownKeys = true }
    val prettyJson = Json { prettyPrint = true }
    Files.createDirectories(OUTPUT_DIR)

    val ingested = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    for (base in targets) {
        val bundle =
            try {
                buildBundle(base, client, reader)
            } catch (e: PokeApiClient.FetchException) {
                println("[skip] $base — ${e.message?.lines()?.firstOrNull()}")
                skipped += base
                continue
            }
        val outputPath = OUTPUT_DIR.resolve("$base.json")
        Files.writeString(outputPath, prettyJson.encodeToString(EvolutionLineJson.serializer(), bundle))
        println("[ok] $base -> $outputPath (${bundle.learnsets.size} stages, ${bundle.edges.size} edges)")
        ingested += base
    }

    val manifest = ingested.sorted().joinToString("\n", postfix = "\n")
    Files.writeString(OUTPUT_DIR.resolve("index.txt"), manifest)
    println("Wrote manifest: ${OUTPUT_DIR.resolve("index.txt")} (${ingested.size} entries)")
    if (skipped.isNotEmpty()) {
        println("${skipped.size} skipped: ${skipped.joinToString(", ")}")
    }
}

private fun buildBundle(
    base: String,
    client: PokeApiClient,
    reader: Json,
): EvolutionLineJson {
    val speciesRaw = client.fetch(SPECIES_ENDPOINT, base)
    val chainUrl = reader.decodeFromString(PokeApiSpecies.serializer(), speciesRaw).evolutionChain.url
    val chainId = chainUrl.trimEnd('/').substringAfterLast('/')
    val chainRaw = client.fetch(CHAIN_ENDPOINT, chainId)
    val chain = reader.decodeFromString(PokeApiEvolutionChain.serializer(), chainRaw)

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
