package com.pokemon.battle.model

enum class Type {
    NORMAL, FIRE, WATER, ELECTRIC, GRASS, ICE,
    FIGHTING, POISON, GROUND, FLYING, PSYCHIC, BUG,
    ROCK, GHOST, DRAGON, DARK, STEEL, FAIRY;

    companion object {
        /** Returns the type effectiveness multiplier for [attacking] vs [defending]. */
        fun effectiveness(attacking: Type, defending: Type): Double =
            CHART[attacking]?.get(defending) ?: 1.0
    }
}

enum class Effectiveness {
    IMMUNE, NOT_VERY_EFFECTIVE, NEUTRAL, SUPER_EFFECTIVE;

    companion object {
        fun from(multiplier: Double): Effectiveness = when {
            multiplier == 0.0 -> IMMUNE
            multiplier < 1.0 -> NOT_VERY_EFFECTIVE
            multiplier > 1.0 -> SUPER_EFFECTIVE
            else -> NEUTRAL
        }
    }
}

/** Combined effectiveness of [attackingType] against a defender with [defendingTypes]. */
fun typeEffectiveness(attackingType: Type, defendingTypes: List<Type>): Double =
    defendingTypes.fold(1.0) { acc, def -> acc * Type.effectiveness(attackingType, def) }

// Sparse chart — only non-1.0 entries. Missing = neutral (1.0).
private val CHART: Map<Type, Map<Type, Double>> = mapOf(
    Type.NORMAL to mapOf(
        Type.ROCK to 0.5, Type.GHOST to 0.0, Type.STEEL to 0.5
    ),
    Type.FIRE to mapOf(
        Type.FIRE to 0.5, Type.WATER to 0.5, Type.GRASS to 2.0, Type.ICE to 2.0,
        Type.BUG to 2.0, Type.ROCK to 0.5, Type.DRAGON to 0.5, Type.STEEL to 2.0
    ),
    Type.WATER to mapOf(
        Type.FIRE to 2.0, Type.WATER to 0.5, Type.GRASS to 0.5, Type.GROUND to 2.0,
        Type.ROCK to 2.0, Type.DRAGON to 0.5
    ),
    Type.ELECTRIC to mapOf(
        Type.WATER to 2.0, Type.ELECTRIC to 0.5, Type.GRASS to 0.5, Type.GROUND to 0.0,
        Type.FLYING to 2.0, Type.DRAGON to 0.5
    ),
    Type.GRASS to mapOf(
        Type.FIRE to 0.5, Type.WATER to 2.0, Type.GRASS to 0.5, Type.POISON to 0.5,
        Type.GROUND to 2.0, Type.FLYING to 0.5, Type.BUG to 0.5, Type.ROCK to 2.0,
        Type.DRAGON to 0.5, Type.STEEL to 0.5
    ),
    Type.ICE to mapOf(
        Type.FIRE to 0.5, Type.WATER to 0.5, Type.GRASS to 2.0, Type.ICE to 0.5,
        Type.GROUND to 2.0, Type.FLYING to 2.0, Type.DRAGON to 2.0, Type.STEEL to 0.5
    ),
    Type.FIGHTING to mapOf(
        Type.NORMAL to 2.0, Type.ICE to 2.0, Type.POISON to 0.5, Type.FLYING to 0.5,
        Type.PSYCHIC to 0.5, Type.BUG to 0.5, Type.ROCK to 2.0, Type.GHOST to 0.0,
        Type.DARK to 2.0, Type.STEEL to 2.0, Type.FAIRY to 0.5
    ),
    Type.POISON to mapOf(
        Type.POISON to 0.5, Type.GROUND to 0.5, Type.ROCK to 0.5, Type.GHOST to 0.5,
        Type.STEEL to 0.0, Type.GRASS to 2.0, Type.FAIRY to 2.0
    ),
    Type.GROUND to mapOf(
        Type.FIRE to 2.0, Type.ELECTRIC to 2.0, Type.GRASS to 0.5, Type.POISON to 2.0,
        Type.FLYING to 0.0, Type.BUG to 0.5, Type.ROCK to 2.0, Type.STEEL to 2.0
    ),
    Type.FLYING to mapOf(
        Type.ELECTRIC to 0.5, Type.GRASS to 2.0, Type.FIGHTING to 2.0, Type.BUG to 2.0,
        Type.ROCK to 0.5, Type.STEEL to 0.5
    ),
    Type.PSYCHIC to mapOf(
        Type.FIGHTING to 2.0, Type.POISON to 2.0, Type.PSYCHIC to 0.5, Type.DARK to 0.0,
        Type.STEEL to 0.5
    ),
    Type.BUG to mapOf(
        Type.FIRE to 0.5, Type.GRASS to 2.0, Type.FIGHTING to 0.5, Type.POISON to 0.5,
        Type.FLYING to 0.5, Type.PSYCHIC to 2.0, Type.GHOST to 0.5, Type.DARK to 2.0,
        Type.STEEL to 0.5, Type.FAIRY to 0.5
    ),
    Type.ROCK to mapOf(
        Type.FIRE to 2.0, Type.ICE to 2.0, Type.FIGHTING to 0.5, Type.GROUND to 0.5,
        Type.FLYING to 2.0, Type.BUG to 2.0, Type.STEEL to 0.5
    ),
    Type.GHOST to mapOf(
        Type.NORMAL to 0.0, Type.PSYCHIC to 2.0, Type.GHOST to 2.0, Type.DARK to 0.5
    ),
    Type.DRAGON to mapOf(
        Type.DRAGON to 2.0, Type.STEEL to 0.5, Type.FAIRY to 0.0
    ),
    Type.DARK to mapOf(
        Type.FIGHTING to 0.5, Type.PSYCHIC to 2.0, Type.GHOST to 2.0, Type.DARK to 0.5,
        Type.FAIRY to 0.5
    ),
    Type.STEEL to mapOf(
        Type.FIRE to 0.5, Type.WATER to 0.5, Type.ELECTRIC to 0.5, Type.ICE to 2.0,
        Type.ROCK to 2.0, Type.FAIRY to 2.0, Type.STEEL to 0.5
    ),
    Type.FAIRY to mapOf(
        Type.FIRE to 0.5, Type.POISON to 0.5, Type.FIGHTING to 2.0, Type.DRAGON to 2.0,
        Type.DARK to 2.0, Type.STEEL to 0.5
    ),
)
