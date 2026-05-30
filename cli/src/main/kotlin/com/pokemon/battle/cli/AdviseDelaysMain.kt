package com.pokemon.battle.cli

import com.pokemon.battle.data.AlternativeAccess
import com.pokemon.battle.data.DelayFlag
import com.pokemon.battle.data.EvolutionDelayAdvisor
import com.pokemon.battle.data.EvolutionEdge
import com.pokemon.battle.data.EvolutionLine
import com.pokemon.battle.data.EvolutionTrigger
import com.pokemon.battle.data.GenerationMap

/**
 * `advise-delays <species> [--game <version-group>]` — prints the moves you'd lose the
 * level-up opportunity for by evolving, per the [com.pokemon.battle.data.EvolutionDelayAdvisor]
 * rule. With `--game` it reports one version group; without, it organizes the answer by
 * generation (a player reads only their own gen), splitting out individual games where
 * version groups within a generation disagree.
 *
 * Any species works: a line absent from the committed set is ingested from PokeAPI on
 * demand into a gitignored cache (see [OnDemandEvolutionLines]).
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: advise-delays <species> [--game <version-group>]")
        return
    }
    val species = args[0].lowercase()
    val gameIndex = args.indexOf("--game")
    val game = if (gameIndex >= 0 && gameIndex + 1 < args.size) args[gameIndex + 1] else null

    val resolver = OnDemandEvolutionLines()
    val line =
        when (val resolution = resolver.resolve(species)) {
            is OnDemandEvolutionLines.Resolution.Unavailable -> {
                println("No evolution line for '$species' (${resolution.reason}).")
                println("Known lines: ${resolver.loadAll().values.flatMap { it.species }.toSortedSet().joinToString(", ")}")
                return
            }
            is OnDemandEvolutionLines.Resolution.Resolved -> {
                resolution.cachePath?.let { path ->
                    println("'${titleCase(species)}' wasn't in the committed set — fetched its line from PokeAPI.")
                    println("Cached locally at $path (gitignored, not tracked).")
                    println(
                        "To share it, add '${resolution.line.base}' to targets/evolution-lines.txt " +
                            "and run ./gradlew :data-ingestion:ingestEvolutionLines.",
                    )
                    println()
                }
                resolution.line
            }
        }

    println("Evolution-delay advice for ${titleCase(species)} (line: ${titleCase(line.base)})")
    println()
    if (game != null) printSingleGame(line, game) else printByGeneration(line)
}

private fun printSingleGame(
    line: EvolutionLine,
    game: String,
) {
    val flags = EvolutionDelayAdvisor.adviseDelays(line, game)
    if (flags.isEmpty()) {
        println("No delay-worthy moves in $game — every move the pre-evolution learns is kept by level-up.")
        return
    }
    for ((edge, edgeFlags) in flags.groupBy { it.edgeFrom to it.edgeTo }) {
        println("Delaying ${edgeLabel(line, edge.first, edge.second)}:")
        for (flag in edgeFlags) {
            println("  hold ${titleCase(flag.edgeFrom)} to L${flag.holdToLevel} for ${flag.move} — ${altLabel(flag.alternativeAccess)}")
        }
        println()
    }
}

private fun printByGeneration(line: EvolutionLine) {
    var anyShown = false
    for (edge in line.edges) {
        val byGeneration = gamesByGeneration(line, edge)
        if (byGeneration.none { (_, games) -> games.any { it.second.isNotEmpty() } }) continue
        anyShown = true
        println("Delaying ${edgeLabel(line, edge.from, edge.to)}:")
        for ((generation, games) in byGeneration) {
            if (games.any { it.second.isNotEmpty() }) printGeneration(generation, games)
        }
        println()
    }
    if (!anyShown) {
        println("No delay-worthy moves in any generation — this line evolves freely.")
    }
}

/** generation -> [(versionGroup, flags-for-this-edge)], generations in play order. */
private fun gamesByGeneration(
    line: EvolutionLine,
    edge: EvolutionEdge,
): List<Pair<String, List<Pair<String, List<DelayFlag>>>>> {
    val byGeneration = mutableMapOf<String, MutableList<Pair<String, List<DelayFlag>>>>()
    for (versionGroup in line.versionGroups()) {
        val generation = GenerationMap.generationOf(versionGroup) ?: continue
        val flags =
            EvolutionDelayAdvisor.adviseDelays(line, versionGroup)
                .filter { it.edgeFrom == edge.from && it.edgeTo == edge.to }
        byGeneration.getOrPut(generation) { mutableListOf() }.add(versionGroup to flags)
    }
    return byGeneration.entries.sortedBy { GenerationMap.generationOrder(it.key) }.map { it.key to it.value }
}

private fun printGeneration(
    generation: String,
    games: List<Pair<String, List<DelayFlag>>>,
) {
    val byFingerprint = games.groupBy { fingerprint(it.second) }
    if (byFingerprint.size == 1) {
        // every version group in this generation agrees — no need to name games.
        println("  gen $generation:")
        printMoveLines(games.first { it.second.isNotEmpty() }.second)
        return
    }
    // Version groups within the generation disagree — name the games rather than collapse.
    // Most-urgent subgroup first (a must-delay move), "evolve freely" last.
    val ordered =
        byFingerprint.entries.sortedWith(
            compareBy(
                { if (it.key.isEmpty()) 1 else 0 },
                { if (it.value.first().second.any { f -> f.alternativeAccess == AlternativeAccess.NONE }) 0 else 1 },
                { it.value.first().second.minOfOrNull { f -> f.holdToLevel } ?: Int.MAX_VALUE },
            ),
        )
    for ((moves, entries) in ordered) {
        val gameNames = entries.joinToString(", ") { titleCase(it.first) }
        if (moves.isEmpty()) {
            println("  gen $generation ($gameNames): evolve freely")
        } else {
            println("  gen $generation ($gameNames):")
            printMoveLines(entries.first().second)
        }
    }
}

private fun printMoveLines(flags: List<DelayFlag>) {
    for (flag in flags.sortedWith(FLAG_ORDER)) {
        println("      L${flag.holdToLevel}  ${flag.move} — ${altLabel(flag.alternativeAccess)}")
    }
}

/** Stable identity of an edge's flag set in one version group, for grouping games that agree. */
private fun fingerprint(flags: List<DelayFlag>): String =
    flags.sortedBy { it.move }.joinToString(";") { "${it.move}:${it.holdToLevel}:${it.alternativeAccess}" }

private fun edgeLabel(
    line: EvolutionLine,
    from: String,
    to: String,
): String {
    val edge: EvolutionEdge? = line.edges.firstOrNull { it.from == from && it.to == to }
    val gate =
        when (edge?.trigger) {
            EvolutionTrigger.LEVEL_UP -> edge.minLevel?.let { "evolves at L$it" } ?: "evolves on level-up"
            EvolutionTrigger.USE_ITEM -> "evolves with ${edge.item ?: "a stone"}"
            EvolutionTrigger.TRADE -> "evolves by trade"
            else -> "evolves"
        }
    return "${titleCase(from)} → ${titleCase(to)} ($gate)"
}

/** Most urgent first: unobtainable (NONE), then by how long you must stay unevolved. */
private val FLAG_ORDER =
    compareBy<DelayFlag>(
        { if (it.alternativeAccess == AlternativeAccess.NONE) 0 else 1 },
        { it.holdToLevel },
        { it.move },
    )

private fun altLabel(access: AlternativeAccess): String =
    when (access) {
        AlternativeAccess.NONE -> "no other way to get it on the evolved form (must delay)"
        AlternativeAccess.RELEARN_ONLY -> "else relearn-only via the Move Reminder"
        AlternativeAccess.MACHINE -> "else available by TM"
        AlternativeAccess.TUTOR -> "else from a move tutor"
        AlternativeAccess.EGG -> "else an egg move"
    }

private fun titleCase(slug: String): String = slug.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
