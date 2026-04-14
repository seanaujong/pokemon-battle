package com.pokemon.battle.model

enum class Nature(val boosted: StatType?, val penalized: StatType?) {
    // Neutral natures (5)
    HARDY(null, null),
    DOCILE(null, null),
    SERIOUS(null, null),
    BASHFUL(null, null),
    QUIRKY(null, null),

    // Attack-boosting
    LONELY(StatType.ATTACK, StatType.DEFENSE),
    BRAVE(StatType.ATTACK, StatType.SPEED),
    ADAMANT(StatType.ATTACK, StatType.SPECIAL_ATTACK),
    NAUGHTY(StatType.ATTACK, StatType.SPECIAL_DEFENSE),

    // Defense-boosting
    BOLD(StatType.DEFENSE, StatType.ATTACK),
    RELAXED(StatType.DEFENSE, StatType.SPEED),
    IMPISH(StatType.DEFENSE, StatType.SPECIAL_ATTACK),
    LAX(StatType.DEFENSE, StatType.SPECIAL_DEFENSE),

    // Special Attack-boosting
    MODEST(StatType.SPECIAL_ATTACK, StatType.ATTACK),
    MILD(StatType.SPECIAL_ATTACK, StatType.DEFENSE),
    QUIET(StatType.SPECIAL_ATTACK, StatType.SPEED),
    RASH(StatType.SPECIAL_ATTACK, StatType.SPECIAL_DEFENSE),

    // Special Defense-boosting
    CALM(StatType.SPECIAL_DEFENSE, StatType.ATTACK),
    GENTLE(StatType.SPECIAL_DEFENSE, StatType.DEFENSE),
    SASSY(StatType.SPECIAL_DEFENSE, StatType.SPEED),
    CAREFUL(StatType.SPECIAL_DEFENSE, StatType.SPECIAL_ATTACK),

    // Speed-boosting
    TIMID(StatType.SPEED, StatType.ATTACK),
    HASTY(StatType.SPEED, StatType.DEFENSE),
    JOLLY(StatType.SPEED, StatType.SPECIAL_ATTACK),
    NAIVE(StatType.SPEED, StatType.SPECIAL_DEFENSE),
    ;

    fun modifier(stat: StatType): Double =
        when (stat) {
            boosted -> 1.1
            penalized -> 0.9
            else -> 1.0
        }
}
