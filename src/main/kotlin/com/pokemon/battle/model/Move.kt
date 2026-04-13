package com.pokemon.battle.model

enum class MoveCategory { PHYSICAL, SPECIAL }

data class Move(
    val name: String,
    val type: Type,
    val category: MoveCategory,
    val power: Int,
    val priority: Int = 0
)
