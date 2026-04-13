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
)
