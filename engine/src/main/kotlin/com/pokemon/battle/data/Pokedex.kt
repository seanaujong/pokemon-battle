package com.pokemon.battle.data

import com.pokemon.battle.model.Species
import com.pokemon.battle.model.Type
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

object Pokedex {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(input: InputStream): Map<String, Species> {
        return input.bufferedReader().useLines { lines ->
            lines.drop(1) // skip header
                .filter { it.isNotBlank() }
                .map { parseLine(it) }
                .associateBy { it.name }
        }
    }

    fun loadFromClasspath(path: String = "data/species.csv"): Map<String, Species> {
        val stream =
            Pokedex::class.java.classLoader.getResourceAsStream(path)
                ?: error("Species CSV not found on classpath: $path")
        return load(stream)
    }

    /**
     * Loads every `*.json` file under [directory] as a [SpeciesJson] and returns a
     * map keyed by species name. Complements [loadFromClasspath] (CSV); see diary 041.
     */
    fun loadFromJsonDirectory(directory: Path): Map<String, Species> {
        require(Files.isDirectory(directory)) { "Not a directory: $directory" }
        return Files.list(directory).use { stream ->
            stream
                .filter { it.extension == "json" }
                .filter { it.fileName.toString() != "index.txt" }
                .map { json.decodeFromString(SpeciesJson.serializer(), it.readText()).toDomain() }
                .toList()
                .associateBy { it.name }
        }
    }

    /**
     * Loads ingested species from the JVM classpath. Reads
     * `pokedex/species/index.txt` (the manifest written by the ingestion CLI) and
     * pulls each listed `<slug>.json`. This is the runtime-friendly counterpart to
     * [loadFromJsonDirectory]; together with [loadFromClasspath] (legacy CSV) the
     * three loaders cover the test, runtime, and legacy paths.
     */
    fun loadJsonFromClasspath(
        manifestPath: String = "pokedex/species/index.txt",
        speciesDir: String = "pokedex/species",
    ): Map<String, Species> {
        val classLoader = Pokedex::class.java.classLoader
        val manifestStream =
            classLoader.getResourceAsStream(manifestPath)
                ?: error("Species manifest not found on classpath: $manifestPath")
        val slugs =
            manifestStream.bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            }
        return slugs
            .map { slug ->
                val resourcePath = "$speciesDir/$slug.json"
                val stream =
                    classLoader.getResourceAsStream(resourcePath)
                        ?: error("Manifest references missing resource: $resourcePath")
                json.decodeFromString(SpeciesJson.serializer(), stream.bufferedReader().readText()).toDomain()
            }
            .associateBy { it.name }
    }

    private fun parseLine(line: String): Species {
        val parts = line.split(",").map { it.trim() }
        require(parts.size >= 9) { "Malformed species line (expected 9 columns): $line" }

        val name = parts[0]
        val type1 = parseType(parts[1], name)
        val type2 = if (parts[2].isNotEmpty()) parseType(parts[2], name) else null
        val types = listOfNotNull(type1, type2)

        return Species(
            name = name,
            types = types,
            baseHp = parts[3].toInt(),
            baseAttack = parts[4].toInt(),
            baseDefense = parts[5].toInt(),
            baseSpecialAttack = parts[6].toInt(),
            baseSpecialDefense = parts[7].toInt(),
            baseSpeed = parts[8].toInt(),
        )
    }

    private fun parseType(
        value: String,
        speciesName: String,
    ): Type {
        return try {
            Type.valueOf(value)
        } catch (ex: IllegalArgumentException) {
            error("Unknown type '$value' for species '$speciesName': ${ex.message}")
        }
    }
}
