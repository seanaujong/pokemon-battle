package com.pokemon.battle.engine

import com.pokemon.battle.model.*

data class DamageResult(val damage: Int, val effectiveness: Effectiveness)

fun interface DamageCalculator {
    fun calculate(
        attacker: PokemonState,
        defender: PokemonState,
        move: Move,
        roll: (IntRange) -> Int,
        spreadModifier: Double,
    ): DamageResult
}

/**
 * Standard Gen V+ damage formula with IVs/EVs/Nature.
 * Gen-specific rules: burn penalty (0.5x physical), STAB (1.5x), formula structure.
 */
class GenVDamageCalculator(
    private val typeChart: TypeChart = StandardTypeChart,
) : DamageCalculator {
    override fun calculate(
        attacker: PokemonState,
        defender: PokemonState,
        move: Move,
        roll: (IntRange) -> Int,
        spreadModifier: Double,
    ): DamageResult {
        val level = attacker.pokemon.level

        val isPhysical = move.category == MoveCategory.PHYSICAL
        val atkStat = if (isPhysical) StatType.ATTACK else StatType.SPECIAL_ATTACK
        val defStat = if (isPhysical) StatType.DEFENSE else StatType.SPECIAL_DEFENSE
        val atkStage = if (isPhysical) attacker.statStages.attack else attacker.statStages.specialAttack
        val defStage = if (isPhysical) defender.statStages.defense else defender.statStages.specialDefense

        val atk = (attacker.pokemon.calcStat(atkStat) * stageMultiplier(atkStage)).toInt()
        val def = (defender.pokemon.calcStat(defStat) * stageMultiplier(defStage)).toInt()

        val burnMod = if (attacker.status == StatusCondition.BURN && isPhysical) 0.5 else 1.0

        val typeMultiplier = typeChart.effectiveness(move.type, defender.effectiveTypes)
        val effectiveness = Effectiveness.from(typeMultiplier)

        val stab = if (move.type in attacker.effectiveTypes) 1.5 else 1.0

        val randomRoll = roll(85..100)

        val baseDamage = ((2.0 * level / 5.0 + 2.0) * move.power * atk / def) / 50.0 + 2.0
        val damage =
            (baseDamage * stab * typeMultiplier * burnMod * spreadModifier * randomRoll / 100.0).toInt()
                .coerceAtLeast(if (typeMultiplier > 0.0) 1 else 0)

        return DamageResult(damage, effectiveness)
    }
}

/** Convenience function using the default Gen V+ calculator with standard type chart. */
fun calculateDamage(
    attacker: PokemonState,
    defender: PokemonState,
    move: Move,
    roll: (IntRange) -> Int = { range -> range.random() },
    spreadModifier: Double = 1.0,
): DamageResult = GenVDamageCalculator().calculate(attacker, defender, move, roll, spreadModifier)
