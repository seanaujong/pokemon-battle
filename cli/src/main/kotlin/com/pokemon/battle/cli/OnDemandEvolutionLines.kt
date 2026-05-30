package com.pokemon.battle.cli

import com.pokemon.battle.data.EvolutionLine
import com.pokemon.battle.data.EvolutionLineDex
import com.pokemon.battle.data.EvolutionLineJson
import com.pokemon.battle.ingest.EvolutionLineIngestor
import com.pokemon.battle.ingest.fetch.PokeApiClient
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the evolution line for a species, ingesting from PokeAPI on demand when no
 * committed (curated) line and no locally-cached line contains it. On-demand bundles
 * land in a gitignored cache ([cacheDir], under `.cache/`) — never tracked; the advisor
 * reads them alongside the committed set. Promoting a line to the shared, committed set
 * stays the deliberate batch path (add to `targets/evolution-lines.txt`, run
 * `:data-ingestion:ingestEvolutionLines`).
 *
 * [buildBundle] is injected so tests drive the miss -> ingest -> resolve path with no
 * network; the default delegates to [EvolutionLineIngestor].
 */
class OnDemandEvolutionLines(
    private val cacheDir: Path = Path.of(".cache/derived/evolution-lines"),
    private val buildBundle: (String) -> EvolutionLineJson = { EvolutionLineIngestor.buildBundle(it) },
) {
    /** Outcome of resolving a species to its line. */
    sealed interface Resolution {
        /**
         * The line was found. [cachePath] is non-null only when this call freshly
         * ingested it (so the caller can report where it landed); null when the line
         * was already present in the committed or cached set.
         */
        data class Resolved(
            val line: EvolutionLine,
            val cachePath: Path?,
        ) : Resolution

        /** No line could be found locally or from PokeAPI. [reason] is human-facing. */
        data class Unavailable(val reason: String) : Resolution
    }

    /** Committed (curated) lines plus any locally-cached on-demand bundles; committed wins on overlap. */
    fun loadAll(): Map<String, EvolutionLine> = cachedLines() + EvolutionLineDex.loadFromClasspath()

    private fun cachedLines(): Map<String, EvolutionLine> =
        if (Files.isDirectory(cacheDir)) EvolutionLineDex.loadFromJsonDirectory(cacheDir) else emptyMap()

    fun resolve(species: String): Resolution {
        EvolutionLineDex.lineContaining(loadAll(), species)?.let {
            return Resolution.Resolved(it, cachePath = null)
        }
        val bundle =
            try {
                buildBundle(species)
            } catch (e: PokeApiClient.FetchException) {
                return Resolution.Unavailable(e.message?.lines()?.firstOrNull() ?: "PokeAPI fetch failed")
            } catch (e: IOException) {
                return Resolution.Unavailable("couldn't reach PokeAPI (offline?): ${e.message}")
            }
        val line = bundle.toDomain()
        if (species !in line.species) {
            return Resolution.Unavailable("PokeAPI returned the ${line.base} line, which does not contain '$species'")
        }
        val cachePath = EvolutionLineIngestor.writeBundle(bundle, cacheDir)
        return Resolution.Resolved(line, cachePath = cachePath)
    }
}
