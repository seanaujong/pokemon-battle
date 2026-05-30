package com.pokemon.battle.data

/**
 * The best route by which a reachable later form can still pick up a move *without*
 * level-up. Ordered NONE-first for display urgency. NONE means the move is
 * unobtainable on the evolved line by any route — the strongest reason to delay.
 */
enum class AlternativeAccess {
    NONE,
    RELEARN_ONLY,
    MACHINE,
    TUTOR,
    EGG,
}

/**
 * A move you'd lose the level-up opportunity for by evolving at [edgeTo]'s gate.
 * Hold the pre-evolution to [holdToLevel] before evolving to keep the move.
 */
data class DelayFlag(
    val move: String,
    val edgeFrom: String,
    val edgeTo: String,
    val holdToLevel: Int,
    val evolveAtLevel: Int?,
    val alternativeAccess: AlternativeAccess,
)

/**
 * Flags evolutions worth delaying. The rule (diary 103): flag a move only when
 * evolving causes a *genuine loss of the level-up opportunity* — the pre-evo learns
 * it by leveling, but no reachable later form relearns it by *reachable* level-up
 * (level >= 2). A form that still learns it by level-up later (Noctowl) is not a
 * loss; a form that only has it at level 1 (relearn-only) is.
 *
 * Pure function of one [EvolutionLine] + a version group. No I/O, no mutation of input.
 */
object EvolutionDelayAdvisor {
    /** Level 1 is relearn-only — you can't level *into* it — so the floor for a "kept" move is 2. */
    private const val REACHABLE_LEVEL_FLOOR = 2

    fun adviseDelays(
        line: EvolutionLine,
        versionGroup: String,
    ): List<DelayFlag> {
        val flags = mutableListOf<DelayFlag>()
        for (edge in line.edges) {
            if (!evolvedFormExists(line, edge, versionGroup)) continue
            val reachable = line.reachableFrom(edge.to)
            for ((move, holdTo) in earliestLevelUpLevels(line.movesOf(edge.from, versionGroup))) {
                if (!isDelayCandidate(edge, holdTo)) continue
                if (retainedByLevelUp(line, versionGroup, reachable, move)) continue
                flags +=
                    DelayFlag(
                        move = move,
                        edgeFrom = edge.from,
                        edgeTo = edge.to,
                        holdToLevel = holdTo,
                        evolveAtLevel = edge.minLevel,
                        alternativeAccess = alternativeAccess(line, versionGroup, reachable, move),
                    )
            }
        }
        return flags.sortedWith(
            compareBy(
                { if (it.alternativeAccess == AlternativeAccess.NONE) 0 else 1 },
                { it.holdToLevel },
                { it.move },
            ),
        )
    }

    /**
     * Whether [edge]'s evolved form exists in [versionGroup]. The bundle records a
     * version group for a form only in games the form appears in, so a missing entry
     * means the evolution isn't available there (Roserade and the shiny stone are gen
     * IV; Roselia pre-dates them). You can't lose a move to an evolution you can't
     * perform, so the edge contributes no flags — without this, every pre-evo level-up
     * move would be flagged "must delay" for a generation lacking the evolved form.
     *
     * Presence is keyed, not list-non-empty: a form that exists but learns nothing of
     * interest (a gutted stone-evo learnset) still has an entry, and its edge is real.
     */
    private fun evolvedFormExists(
        line: EvolutionLine,
        edge: EvolutionEdge,
        versionGroup: String,
    ): Boolean = line.hasLearnsetIn(edge.to, versionGroup)

    /** move -> earliest level at which the pre-evo learns it by level-up. */
    private fun earliestLevelUpLevels(moves: List<MoveAcquisition>): Map<String, Int> =
        moves
            .filter { it.method == LearnMethod.LEVEL_UP }
            .groupBy { it.move }
            .mapValues { (_, list) -> list.minOf { it.level } }

    /**
     * True if learning the move at [holdTo] on the pre-evo is something evolving could
     * cost you. A base move (level 1) carries through evolution. For a level-gated
     * evolution, anything learned at or before the gate is acquired before you'd
     * evolve anyway; stone/trade evolutions have no gate, so any non-base move counts.
     */
    private fun isDelayCandidate(
        edge: EvolutionEdge,
        holdTo: Int,
    ): Boolean {
        if (holdTo < REACHABLE_LEVEL_FLOOR) return false
        val gate = edge.minLevel
        return !(edge.trigger == EvolutionTrigger.LEVEL_UP && gate != null && holdTo <= gate)
    }

    private fun retainedByLevelUp(
        line: EvolutionLine,
        versionGroup: String,
        reachable: Set<String>,
        move: String,
    ): Boolean =
        reachable.any { stage ->
            line.movesOf(stage, versionGroup).any {
                it.move == move && it.method == LearnMethod.LEVEL_UP && it.level >= REACHABLE_LEVEL_FLOOR
            }
        }

    private fun alternativeAccess(
        line: EvolutionLine,
        versionGroup: String,
        reachable: Set<String>,
        move: String,
    ): AlternativeAccess {
        val acquisitions = reachable.flatMap { line.movesOf(it, versionGroup) }.filter { it.move == move }
        val relearnOnly = acquisitions.any { it.method == LearnMethod.LEVEL_UP && it.level < REACHABLE_LEVEL_FLOOR }
        return when {
            acquisitions.any { it.method == LearnMethod.MACHINE } -> AlternativeAccess.MACHINE
            relearnOnly -> AlternativeAccess.RELEARN_ONLY
            acquisitions.any { it.method == LearnMethod.TUTOR } -> AlternativeAccess.TUTOR
            acquisitions.any { it.method == LearnMethod.EGG } -> AlternativeAccess.EGG
            else -> AlternativeAccess.NONE
        }
    }
}
