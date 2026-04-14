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
)
