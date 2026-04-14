package com.pokemon.battle.ingest.smogon

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines

private val TARGETS_FILE: Path = Path.of("targets/smogon.txt")
private val FULL_CACHE_ROOT: Path = Path.of(".cache/smogon")
private val PROJECTED_ROOT: Path = Path.of("data/raw/smogon")
private val OUTPUT_DIR: Path = Path.of("data/smogon")

/**
 * Reads `targets/smogon.txt` (one `<month> <format> <ratingCutoff>` triple per line),
 * fetches each chaos file, projects to a committed raw copy, and transforms into a
 * compact [SmogonFormatSets] JSON per target.
 *
 * Three-tier ELT per diary 041:
 *   `.cache/smogon/<month>/chaos/<format>-<rating>.json`   — verbatim, gitignored
 *   `data/raw/smogon/<month>/<format>-<rating>.json`       — projected, committed
 *   `data/smogon/<format>-<rating>-top-sets.json`          — transformed, committed
 */
fun main() {
    val targets =
        TARGETS_FILE
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { parseTargetLine(it) }

    val client = SmogonClient(cacheRoot = FULL_CACHE_ROOT)
    val prettyJson = Json { prettyPrint = true }
    Files.createDirectories(OUTPUT_DIR)

    for (target in targets) {
        val hitCache =
            Files.exists(
                FULL_CACHE_ROOT
                    .resolve(target.month)
                    .resolve("chaos")
                    .resolve("${target.format}-${target.ratingCutoff}.json"),
            )

        val rawJson = client.fetchChaos(target.month, target.format, target.ratingCutoff)
        val projected = SmogonProjection.projectChaos(rawJson)

        val projectedDir = PROJECTED_ROOT.resolve(target.month)
        Files.createDirectories(projectedDir)
        val projectedPath = projectedDir.resolve("${target.format}-${target.ratingCutoff}.json")
        Files.writeString(projectedPath, projected)

        val formatSets =
            SmogonTransform.transform(projected, target.month, target.ratingCutoff)
        val outputPath = OUTPUT_DIR.resolve("${target.format}-${target.ratingCutoff}-top-sets.json")
        Files.writeString(
            outputPath,
            prettyJson.encodeToString(SmogonFormatSets.serializer(), formatSets),
        )

        val source = if (hitCache) "cache" else "fetch"
        println(
            "[$source] ${target.format}@${target.month} cutoff=${target.ratingCutoff} -> $outputPath",
        )
    }
}

private data class Target(val month: String, val format: String, val ratingCutoff: Int)

private fun parseTargetLine(line: String): Target {
    val parts = line.split(Regex("\\s+"))
    require(parts.size == 3) {
        "Target line must be '<YYYY-MM> <format> <ratingCutoff>', got: $line"
    }
    return Target(
        month = parts[0],
        format = parts[1],
        ratingCutoff = parts[2].toInt(),
    )
}
