package com.pokemon.battle.model

data class StatBlock(
    val hp: Int,
    val attack: Int,
    val defense: Int,
    val specialAttack: Int,
    val specialDefense: Int,
    val speed: Int,
) {
    fun forStat(stat: StatType): Int =
        when (stat) {
            StatType.ATTACK -> attack
            StatType.DEFENSE -> defense
            StatType.SPECIAL_ATTACK -> specialAttack
            StatType.SPECIAL_DEFENSE -> specialDefense
            StatType.SPEED -> speed
        }

    fun all(predicate: (Int) -> Boolean): Boolean =
        predicate(hp) && predicate(attack) && predicate(defense) &&
            predicate(specialAttack) && predicate(specialDefense) && predicate(speed)

    companion object {
        fun uniform(value: Int) = StatBlock(value, value, value, value, value, value)
    }
}
