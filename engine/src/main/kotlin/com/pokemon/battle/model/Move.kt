package com.pokemon.battle.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

enum class MoveCategory { PHYSICAL, SPECIAL, STATUS }

@Serializable
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
     *
     * Marked `@Transient` because `IntRange` has no kotlinx-serialization support
     * out of the box. Multi-hit count is not needed for event auditing — the
     * emitted `DamageDealt` events per hit tell the complete story — so we
     * accept losing this field on round-trip.
     */
    @Transient
    val hitCount: IntRange? = null,
)
