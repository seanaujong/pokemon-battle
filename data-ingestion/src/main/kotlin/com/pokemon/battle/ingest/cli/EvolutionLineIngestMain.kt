package com.pokemon.battle.ingest.cli

import com.pokemon.battle.ingest.EvolutionLineIngestor
import com.pokemon.battle.ingest.fetch.PokeApiClient
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines

private val TARGETS_FILE: Path = Path.of("targets/evolution-lines.txt")
private val CACHE_ROOT: Path = Path.of(".cache/pokeapi")
private val OUTPUT_DIR: Path = Path.of("data/src/main/resources/dex/evolution-lines")

/**
 * Batch-ingests evolution-line bundles for the delay advisor. Reads the curated base
 * species in [TARGETS_FILE], folds [EvolutionLineIngestor.buildBundle] over them, and
 * writes a bundle per line plus an `index.txt` manifest into committed resources.
 * Orchestration only — fetching and shaping live in the ingestor it folds over.
 */
fun main() {
    val targets =
        TARGETS_FILE
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    val client = PokeApiClient(cacheRoot = CACHE_ROOT)
    val reader = Json { ignoreUnknownKeys = true }
    Files.createDirectories(OUTPUT_DIR)

    val ingested = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    for (target in targets) {
        val bundle =
            try {
                EvolutionLineIngestor.buildBundle(target, client, reader)
            } catch (e: PokeApiClient.FetchException) {
                println("[skip] $target — ${e.message?.lines()?.firstOrNull()}")
                skipped += target
                continue
            }
        val outputPath = EvolutionLineIngestor.writeBundle(bundle, OUTPUT_DIR)
        println("[ok] $target -> $outputPath (${bundle.learnsets.size} stages, ${bundle.edges.size} edges)")
        ingested += bundle.base
    }

    val manifest = ingested.sorted().joinToString("\n", postfix = "\n")
    Files.writeString(OUTPUT_DIR.resolve("index.txt"), manifest)
    println("Wrote manifest: ${OUTPUT_DIR.resolve("index.txt")} (${ingested.size} entries)")
    if (skipped.isNotEmpty()) {
        println("${skipped.size} skipped: ${skipped.joinToString(", ")}")
    }
}
