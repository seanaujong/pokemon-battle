package com.pokemon.battle.data

/**
 * How a species evolves into the next stage. Mirrors PokeAPI's evolution-trigger
 * slugs, collapsed to the cases the delay advisor distinguishes: level-up evolutions
 * have a level gate (you can decline with B); item/trade evolutions are player-timed.
 */
enum class EvolutionTrigger {
    LEVEL_UP,
    USE_ITEM,
    TRADE,
    OTHER,
    ;

    companion object {
        fun fromSlug(slug: String): EvolutionTrigger =
            when (slug) {
                "level-up" -> LEVEL_UP
                "use-item" -> USE_ITEM
                "trade" -> TRADE
                else -> OTHER
            }
    }
}

/**
 * How a move is acquired. The advisor cares specifically about LEVEL_UP (the
 * opportunity that evolving can cost you); the others are the fallback routes that
 * remain after evolution (TM/tutor/breeding) and a level-1 LEVEL_UP entry, which is
 * relearn-only in practice — see [MoveAcquisition].
 */
enum class LearnMethod {
    LEVEL_UP,
    MACHINE,
    TUTOR,
    EGG,
    OTHER,
    ;

    companion object {
        fun fromSlug(slug: String): LearnMethod =
            when (slug) {
                "level-up" -> LEVEL_UP
                "machine" -> MACHINE
                "tutor" -> TUTOR
                "egg" -> EGG
                else -> OTHER
            }
    }
}

/**
 * One way a species learns a move in a given version group. `level` is the level-up
 * level for [LearnMethod.LEVEL_UP] (1 = relearn-only / known-on-evolution, since you
 * can't level *into* level 1); 0 for non-level-up methods.
 */
data class MoveAcquisition(
    val move: String,
    val method: LearnMethod,
    val level: Int,
)

/** A single parent → child evolution step. */
data class EvolutionEdge(
    val from: String,
    val to: String,
    val trigger: EvolutionTrigger,
    val minLevel: Int?,
    val item: String?,
)

/**
 * A full evolution line — every stage's edges plus every stage's learnset, keyed by
 * `species -> versionGroup -> acquisitions`. Self-contained: the delay advisor reads
 * exactly one [EvolutionLine] and needs nothing else.
 */
data class EvolutionLine(
    val base: String,
    val edges: List<EvolutionEdge>,
    val learnsets: Map<String, Map<String, List<MoveAcquisition>>>,
) {
    val species: Set<String>
        get() = (edges.map { it.from } + edges.map { it.to } + base).toSet()

    /** Acquisitions for [species] in [versionGroup], or empty if absent. */
    fun movesOf(
        species: String,
        versionGroup: String,
    ): List<MoveAcquisition> = learnsets[species]?.get(versionGroup).orEmpty()

    /**
     * Whether [species] has a learnset recorded in [versionGroup]. The bundle writes an
     * entry only for games a form appears in, so this is the signal that the form exists
     * in that game — distinct from [movesOf] being empty, which a present-but-gutted
     * learnset also produces.
     */
    fun hasLearnsetIn(
        species: String,
        versionGroup: String,
    ): Boolean = learnsets[species]?.containsKey(versionGroup) == true

    /** Version groups that appear anywhere in this line's learnsets. */
    fun versionGroups(): Set<String> = learnsets.values.flatMap { it.keys }.toSet()

    /**
     * The set of stages reachable from [start] by following edges forward, including
     * [start] itself. Used for the line-level roll-up: "if I evolve here, can any
     * later form still learn the move by level-up?"
     */
    fun reachableFrom(start: String): Set<String> {
        val seen = mutableSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (edge in edges.filter { it.from == current }) {
                if (seen.add(edge.to)) queue.add(edge.to)
            }
        }
        return seen
    }
}
