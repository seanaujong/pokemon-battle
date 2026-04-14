package com.pokemon.battle.ingest.smogon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readLines
import kotlin.io.path.readText

private val SMOGON_DIR: Path = Path.of("data/smogon")
private val SPECIES_TARGETS: Path = Path.of("targets/species.txt")
private val ALIASES_FILE: Path = Path.of("data/aliases.json")

@Serializable
private data class AliasesFile(val aliases: Map<String, String>)

/**
 * Bridges Smogon top-sets into the PokeAPI ingestion target list.
 *
 * Reads every top-sets file under `data/smogon/`, collects unique species names,
 * converts each to a PokeAPI slug (`Great Tusk` to `great-tusk`), unions with the
 * existing `targets/species.txt`, and rewrites the file sorted + deduped.
 *
 * Failures (a slug PokeAPI doesn't recognize) surface during the next
 * `:data-ingestion:run` (PokeAPI fetch); fix the slug mapping below as needed.
 *
 * Diary 041 Phase 3: this is the "use Smogon to recommend what to pull" step.
 */
fun main() {
    require(Files.isDirectory(SMOGON_DIR)) { "Run :data-ingestion:ingestSmogon first to populate $SMOGON_DIR" }

    val json = Json { ignoreUnknownKeys = true }
    val newSpecies =
        Files.list(SMOGON_DIR).use { stream ->
            stream
                .filter { it.extension == "json" }
                .flatMap { file ->
                    val sets = json.decodeFromString(SmogonFormatSets.serializer(), file.readText())
                    sets.topSpecies.stream().map { it.name }
                }
                .toList()
        }

    val newSlugs = newSpecies.map { toPokeApiSlug(it) }.toSet()

    val existing =
        if (Files.exists(SPECIES_TARGETS)) {
            SPECIES_TARGETS.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        } else {
            emptySet()
        }

    val added = newSlugs - existing
    val combined = (existing + newSlugs).sorted()

    Files.writeString(SPECIES_TARGETS, combined.joinToString("\n", postfix = "\n"))
    println("Wrote ${combined.size} species targets to $SPECIES_TARGETS (${added.size} new from Smogon).")
    if (added.isNotEmpty()) {
        println("New: ${added.sorted().joinToString(", ")}")
    }
}

/**
 * Smogon writes a species' base name when the species has multiple forms but
 * Smogon's competitive context implies a default. PokeAPI requires the explicit
 * default-form slug. The mapping lives in [ALIASES_FILE] — a committed data
 * file per diary 067's aliases seam. Extending a mapping is a JSON edit, not a
 * code change.
 */
private val SLUG_ALIASES: Map<String, String> by lazy { loadAliases() }

private fun loadAliases(): Map<String, String> {
    require(Files.exists(ALIASES_FILE)) { "Alias file missing: $ALIASES_FILE" }
    val json = Json { ignoreUnknownKeys = true }
    return json.decodeFromString(AliasesFile.serializer(), ALIASES_FILE.readText()).aliases
}

// Smogon display name to PokeAPI slug. Lowercase, spaces to dashes, drop dots
// and apostrophes (so "Mr. Mime" becomes "mr-mime", "Farfetch'd" becomes "farfetchd").
// Form-suffixed names like "Tatsugiri-Stretchy" mostly pass through; for
// default-form basenames PokeAPI rejects (Tatsugiri, Urshifu, etc.), see
// [ALIASES_FILE] above.
fun toPokeApiSlug(displayName: String): String {
    val basic =
        displayName
            .lowercase()
            .replace(' ', '-')
            .replace(".", "")
            .replace("'", "")
            .replace(":", "")
    return SLUG_ALIASES[basic] ?: basic
}
