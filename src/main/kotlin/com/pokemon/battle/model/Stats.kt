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
}

/** Standard Gen V+ HP stat formula (no IVs/EVs/Nature for now). */
fun calcMaxHp(base: Int, level: Int): Int =
    ((2 * base * level) / 100) + level + 10

/** Standard Gen V+ non-HP stat formula (no IVs/EVs/Nature for now). */
fun calcStat(base: Int, level: Int): Int =
    ((2 * base * level) / 100) + 5

/** Stat stage multiplier: stage 0 = 1.0, +1 = 1.5, -1 = 0.667, etc. */
fun stageMultiplier(stage: Int): Double {
    val clamped = stage.coerceIn(-6, 6)
    return if (clamped >= 0) (2.0 + clamped) / 2.0 else 2.0 / (2.0 - clamped)
}
