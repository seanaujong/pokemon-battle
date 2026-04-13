package com.pokemon.battle

data class Species(
    val name: String,
    val types: List<Type>,
    val baseHp: Int,
    val baseAttack: Int,
    val baseDefense: Int,
    val baseSpecialAttack: Int,
    val baseSpecialDefense: Int,
    val baseSpeed: Int
)
