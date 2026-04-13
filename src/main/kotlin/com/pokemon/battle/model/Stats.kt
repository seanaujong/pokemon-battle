package com.pokemon.battle.model

data class StatStages(
    val attack: Int = 0,
    val defense: Int = 0,
    val specialAttack: Int = 0,
    val specialDefense: Int = 0,
    val speed: Int = 0
) {
    init {
        require(attack in -6..6)
        require(defense in -6..6)
        require(specialAttack in -6..6)
        require(specialDefense in -6..6)
        require(speed in -6..6)
    }

    fun withChange(stat: StatType, stages: Int): StatStages = when (stat) {
        StatType.ATTACK -> copy(attack = (attack + stages).coerceIn(-6, 6))
        StatType.DEFENSE -> copy(defense = (defense + stages).coerceIn(-6, 6))
        StatType.SPECIAL_ATTACK -> copy(specialAttack = (specialAttack + stages).coerceIn(-6, 6))
        StatType.SPECIAL_DEFENSE -> copy(specialDefense = (specialDefense + stages).coerceIn(-6, 6))
        StatType.SPEED -> copy(speed = (speed + stages).coerceIn(-6, 6))
    }
}

/** Gen V+ HP formula: ((2*base + iv + ev/4) * level) / 100 + level + 10 */
fun calcMaxHp(base: Int, level: Int, iv: Int = 31, ev: Int = 0): Int =
    ((2 * base + iv + ev / 4) * level) / 100 + level + 10

/** Gen V+ stat formula: (((2*base + iv + ev/4) * level) / 100 + 5) * nature */
fun calcStat(base: Int, level: Int, iv: Int = 31, ev: Int = 0, natureMod: Double = 1.0): Int =
    ((((2 * base + iv + ev / 4) * level) / 100 + 5) * natureMod).toInt()

/** Stat stage multiplier: stage 0 = 1.0, +1 = 1.5, -1 = 0.667, etc. */
fun stageMultiplier(stage: Int): Double {
    val clamped = stage.coerceIn(-6, 6)
    return if (clamped >= 0) (2.0 + clamped) / 2.0 else 2.0 / (2.0 - clamped)
}
