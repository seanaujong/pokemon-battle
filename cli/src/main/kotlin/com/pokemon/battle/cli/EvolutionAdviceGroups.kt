package com.pokemon.battle.cli

import com.pokemon.battle.data.AlternativeAccess
import com.pokemon.battle.data.DelayFlag
import com.pokemon.battle.data.EvolutionDelayAdvisor
import com.pokemon.battle.data.EvolutionEdge
import com.pokemon.battle.data.EvolutionLine
import com.pokemon.battle.data.GenerationMap

/**
 * One block of advise-delays output: the games ([versionGroups]) that share an
 * identical outcome for an edge, and the [flags] that outcome consists of. Empty
 * [flags] means "evolve freely" — the evolution is available in those games but
 * costs no level-up move.
 */
internal data class AdviceGroup(
    val versionGroups: List<String>,
    val flags: List<DelayFlag>,
)

/**
 * Groups the games in which [edge]'s evolution is available by identical advice, so
 * games and generations with the same outcome collapse into one block (gens V–VII
 * need not repeat the same list three times). Version groups where the evolved form
 * doesn't exist are excluded — the evolution can't be performed there.
 *
 * Ordered must-delay-first, then by earliest hold level, then earliest generation;
 * the evolve-freely group, if any, sorts last. Each group's games are in gen order.
 */
internal fun adviceGroups(
    line: EvolutionLine,
    edge: EvolutionEdge,
): List<AdviceGroup> {
    // Both stages must exist in the game for the evolution scenario to be real: Roserade
    // is absent before gen IV (you can't evolve into it), and Budew is absent in gen III
    // (you can't obtain the pre-evo to evolve). Either gap makes the edge inapplicable.
    val available =
        line.versionGroups().filter {
            GenerationMap.generationOf(it) != null &&
                line.hasLearnsetIn(edge.from, it) &&
                line.hasLearnsetIn(edge.to, it)
        }
    val gamesByOutcome = LinkedHashMap<String, MutableList<String>>()
    val flagsByOutcome = HashMap<String, List<DelayFlag>>()
    for (versionGroup in available) {
        val flags =
            EvolutionDelayAdvisor.adviseDelays(line, versionGroup)
                .filter { it.edgeFrom == edge.from && it.edgeTo == edge.to }
        val outcome = fingerprint(flags)
        gamesByOutcome.getOrPut(outcome) { mutableListOf() }.add(versionGroup)
        flagsByOutcome[outcome] = flags
    }
    return gamesByOutcome
        .map { (outcome, games) -> AdviceGroup(games.sortedWith(VERSION_GROUP_ORDER), flagsByOutcome.getValue(outcome)) }
        .sortedWith(GROUP_ORDER)
}

/**
 * "Black White (V), X Y (VI), Sun Moon (VII)" when a group spans generations;
 * "Heartgold Soulsilver, Platinum (gen IV)" when it sits in one. Games are assumed
 * pre-sorted in generation order.
 */
internal fun gamesLabel(versionGroups: List<String>): String {
    val generations = versionGroups.mapNotNull { GenerationMap.generationOf(it) }.toSet()
    return if (generations.size == 1) {
        versionGroups.joinToString(", ") { titleCase(it) } + " (gen ${generations.first()})"
    } else {
        versionGroups.joinToString(", ") { "${titleCase(it)} (${GenerationMap.generationOf(it)})" }
    }
}

/** Stable identity of an edge's flag set in one version group, for grouping games that agree. */
internal fun fingerprint(flags: List<DelayFlag>): String =
    flags.sortedBy { it.move }.joinToString(";") { "${it.move}:${it.holdToLevel}:${it.alternativeAccess}" }

internal fun titleCase(slug: String): String = slug.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

private val VERSION_GROUP_ORDER =
    compareBy<String>({ GenerationMap.generationOrder(GenerationMap.generationOf(it)!!) }, { it })

/** Must-delay first, then by earliest hold level, then earliest generation; evolve-freely last. */
private val GROUP_ORDER =
    compareBy<AdviceGroup>(
        { if (it.flags.isEmpty()) 1 else 0 },
        { if (it.flags.any { f -> f.alternativeAccess == AlternativeAccess.NONE }) 0 else 1 },
        { it.flags.minOfOrNull { f -> f.holdToLevel } ?: Int.MAX_VALUE },
        { GenerationMap.generationOrder(GenerationMap.generationOf(it.versionGroups.first())!!) },
    )
