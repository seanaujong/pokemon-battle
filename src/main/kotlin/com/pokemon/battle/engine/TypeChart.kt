package com.pokemon.battle.engine

import com.pokemon.battle.model.*

/**
 * Calculates type effectiveness. Injectable for Inverse Battles and custom formats.
 */
fun interface TypeChart {
    fun effectiveness(attackingType: Type, defendingTypes: List<Type>): Double
}

/** Standard type chart — delegates to the model layer's chart data. */
val StandardTypeChart = TypeChart { attackingType, defendingTypes ->
    typeEffectiveness(attackingType, defendingTypes)
}

/**
 * Inverse type chart — super-effective becomes not very effective and vice versa.
 * Immunities become neutral.
 */
val InverseTypeChart = TypeChart { attackingType, defendingTypes ->
    val standard = typeEffectiveness(attackingType, defendingTypes)
    when {
        standard == 0.0 -> 1.0       // immune → neutral
        standard > 1.0 -> 1.0 / standard  // super-effective → not very effective (2x → 0.5x, 4x → 0.25x)
        standard < 1.0 -> 1.0 / standard  // not very effective → super-effective (0.5x → 2x, 0.25x → 4x)
        else -> 1.0                   // neutral stays neutral
    }
}
