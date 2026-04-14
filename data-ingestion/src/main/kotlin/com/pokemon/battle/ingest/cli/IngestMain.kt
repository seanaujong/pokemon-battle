package com.pokemon.battle.ingest.cli

import com.pokemon.battle.data.SpeciesJson
import com.pokemon.battle.ingest.fetch.PokeApiClient
import com.pokemon.battle.ingest.fetch.PokeApiProjection
import com.pokemon.battle.ingest.transform.SpeciesTransform
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines

private const val SPECIES_ENDPOINT = "pokemon"
private val TARGETS_FILE: Path = Path.of("targets/species.txt")
private val FULL_CACHE_ROOT: Path = Path.of(".cache/pokeapi")
private val PROJECTED_ROOT: Path = Path.of("data/raw/pokeapi")
private val OUTPUT_DIR: Path = Path.of("data/src/main/resources/pokedex/species")

fun main() {
    val targets =
        TARGETS_FILE
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    val client = PokeApiClient(cacheRoot = FULL_CACHE_ROOT)
    val prettyJson = Json { prettyPrint = true }
    val projectedDir = PROJECTED_ROOT.resolve(SPECIES_ENDPOINT)
    Files.createDirectories(OUTPUT_DIR)
    Files.createDirectories(projectedDir)

    val skipped = mutableListOf<String>()
    for (slug in targets) {
        val hitCache = Files.exists(FULL_CACHE_ROOT.resolve(SPECIES_ENDPOINT).resolve("$slug.json"))
        val rawJson =
            try {
                client.fetch(SPECIES_ENDPOINT, slug)
            } catch (e: PokeApiClient.FetchException) {
                println("[skip] $slug — ${e.message?.lines()?.firstOrNull()}")
                skipped += slug
                continue
            }
        val projected = PokeApiProjection.projectPokemon(rawJson)
        Files.writeString(projectedDir.resolve("$slug.json"), projected)

        val speciesJson = SpeciesTransform.transform(projected)
        val outputPath = OUTPUT_DIR.resolve("$slug.json")
        Files.writeString(outputPath, prettyJson.encodeToString(SpeciesJson.serializer(), speciesJson))

        val source = if (hitCache) "cache" else "fetch"
        println("[$source] $slug -> $outputPath")
    }
    if (skipped.isNotEmpty()) {
        println()
        println("${skipped.size} target(s) skipped (not found in PokeAPI; check the slug):")
        skipped.forEach { println("  $it") }
    }

    // Manifest of slugs successfully ingested — the runtime classpath loader reads
    // this to know which species JSON files to load. Skipped targets are not listed.
    val manifest =
        targets.filter { it !in skipped }
            .sorted()
            .joinToString("\n", postfix = "\n")
    val manifestPath = OUTPUT_DIR.resolve("index.txt")
    Files.writeString(manifestPath, manifest)
    println("Wrote manifest: $manifestPath (${targets.size - skipped.size} entries)")
}
