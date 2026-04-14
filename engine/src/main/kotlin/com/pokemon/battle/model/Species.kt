package com.pokemon.battle.model

data class Species(
    val name: String,
    val types: List<Type>,
    val baseHp: Int,
    val baseAttack: Int,
    val baseDefense: Int,
    val baseSpecialAttack: Int,
    val baseSpecialDefense: Int,
    val baseSpeed: Int,
) {
    fun baseStat(stat: StatType): Int =
        when (stat) {
            StatType.ATTACK -> baseAttack
            StatType.DEFENSE -> baseDefense
            StatType.SPECIAL_ATTACK -> baseSpecialAttack
            StatType.SPECIAL_DEFENSE -> baseSpecialDefense
            StatType.SPEED -> baseSpeed
        }
}
