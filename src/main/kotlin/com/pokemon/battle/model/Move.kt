package com.pokemon.battle.model

enum class MoveCategory { PHYSICAL, SPECIAL, STATUS }

data class Move(
    val name: String,
    val type: Type,
    val category: MoveCategory,
    val power: Int,
    val priority: Int = 0,
    val target: MoveTarget = MoveTarget.ONE_OPPONENT,
    val effects: List<MoveEffect> = emptyList(),
    /**
     * If true, this move fails unless the user has [Volatile.JustSwitchedIn] (Fake Out,
     * First Impression, Mat Block). A lightweight precondition flag — when the
     * move-behavior registry (diary 029) is built, this becomes a `preconditionFails`
     * hook on a `MoveBehavior`.
     */
    val requiresJustSwitchedIn: Boolean = false,
    /**
     * If non-null, this move strikes multiple times in a single use. The range
     * is sampled via `roll(hitCount)` at execution time to pick the hit count
     * (uniform — the true 35/35/15/15 distribution is a future refinement).
     * Each hit independently rolls damage and crit, runs through per-hit
     * intercepts (Sturdy, Focus Sash), and stops early if the target faints.
     * `null` means the move strikes exactly once (default).
     */
    val hitCount: IntRange? = null,
)
