#!/usr/bin/env kotlin

/**
 * Sample analyst script — reads a persisted battle corpus and prints a
 * win-by-strategy table. Exists as diary 085's isolation test: consumes
 * `:persistence` (and transitively `:engine`) from Maven Local WITHOUT
 * depending on the multi-module build. If this runs, module isolation is real.
 *
 * Prerequisites:
 *   ./gradlew :engine:publishToMavenLocal :persistence:publishToMavenLocal
 *
 * Run:
 *   kotlin scripts/analyst-query.main.kts [battles-dir]
 *
 * For Kotlin Jupyter notebooks, paste the imports + body into cells and drop
 * the DependsOn directives — the notebook kernel's `%use` machinery handles
 * Maven-Local-resolved dependencies via a `@file:Repository(...)` equivalent.
 */

@file:Repository("https://repo1.maven.org/maven2/")
@file:Repository("file://~/.m2/repository/")
@file:DependsOn("com.pokemon.battle:persistence:1.0-SNAPSHOT")
@file:DependsOn("com.pokemon.battle:engine:1.0-SNAPSHOT")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

import com.pokemon.battle.persistence.BattleLoader
import com.pokemon.battle.model.Side
import java.nio.file.Path

val dir = Path.of(args.firstOrNull() ?: "battles")

val battles = BattleLoader.loadAll(dir).toList()
println("Loaded ${battles.size} battles from ${dir.toAbsolutePath()}")
println()

// Win rate by side-1 player tag
val bySide1: Map<String?, List<com.pokemon.battle.persistence.PersistedBattle>> =
    battles.groupBy { it.metadata.playerTags[Side.SIDE_1.name] }

println("Win rate when playing as side 1:")
bySide1.entries.sortedByDescending { it.value.size }.forEach { (tag, games) ->
    val wins = games.count { it.winner == Side.SIDE_1 }
    val pct = if (games.isEmpty()) 0.0 else 100.0 * wins / games.size
    println("  %-15s: %3d / %3d (%.1f%%)".format(tag ?: "(untagged)", wins, games.size, pct))
}

// Average turns per battle by format
println()
println("Average turns per battle:")
battles.groupBy { it.metadata.formatTag }
    .forEach { (tag, games) ->
        val avg = games.map { it.turns.size }.average()
        println("  %-20s: %.1f".format(tag, avg))
    }
