package com.pokemon.battle.data

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Loads evolution-line bundles produced by the ingestion CLI. Mirrors [Pokedex]'s
 * directory + classpath-manifest loaders. Lines are keyed by base-species slug.
 */
object EvolutionLineDex {
    private val json = Json { ignoreUnknownKeys = true }

    private const val DEFAULT_MANIFEST = "dex/evolution-lines/index.txt"
    private const val DEFAULT_DIR = "dex/evolution-lines"

    /** Loads every `*.json` bundle under [directory], keyed by base-species slug. */
    fun loadFromJsonDirectory(directory: Path): Map<String, EvolutionLine> {
        require(Files.isDirectory(directory)) { "Not a directory: $directory" }
        return Files.list(directory).use { stream ->
            stream
                .filter { it.extension == "json" }
                .map { json.decodeFromString(EvolutionLineJson.serializer(), it.readText()).toDomain() }
                .toList()
                .associateBy { it.base }
        }
    }

    /**
     * Loads ingested lines from the JVM classpath via the `index.txt` manifest the
     * ingestion CLI writes — the runtime counterpart to [loadFromJsonDirectory].
     */
    fun loadFromClasspath(
        manifestPath: String = DEFAULT_MANIFEST,
        linesDir: String = DEFAULT_DIR,
    ): Map<String, EvolutionLine> {
        val classLoader = EvolutionLineDex::class.java.classLoader
        val manifestStream =
            classLoader.getResourceAsStream(manifestPath)
                ?: error("Evolution-line manifest not found on classpath: $manifestPath")
        val slugs =
            manifestStream.bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            }
        return slugs
            .map { slug ->
                val resourcePath = "$linesDir/$slug.json"
                val stream =
                    classLoader.getResourceAsStream(resourcePath)
                        ?: error("Manifest references missing resource: $resourcePath")
                json.decodeFromString(EvolutionLineJson.serializer(), stream.bufferedReader().readText()).toDomain()
            }
            .associateBy { it.base }
    }

    /** The line that contains [species] at any stage, or null if none do. */
    fun lineContaining(
        lines: Map<String, EvolutionLine>,
        species: String,
    ): EvolutionLine? = lines.values.firstOrNull { species in it.species }
}
