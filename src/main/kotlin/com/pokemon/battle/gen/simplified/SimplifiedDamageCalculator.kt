package com.pokemon.battle.gen.simplified

import com.pokemon.battle.engine.DamageCalculator
import com.pokemon.battle.engine.DamageResult
import com.pokemon.battle.engine.StandardTypeChart
import com.pokemon.battle.engine.TypeChart
import com.pokemon.battle.model.Effectiveness
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.StatType
import com.pokemon.battle.model.Weather
import com.pokemon.battle.model.stageMultiplier

/**
 * Simplified damage formula — intentionally different from Gen V to prove multi-gen works.
 * No STAB, no burn penalty, simpler base formula: (power * atk / def) * type * roll / 100.
 */
class SimplifiedDamageCalculator(
    private val typeChart: TypeChart = StandardTypeChart,
) : DamageCalculator {
    @Suppress("UNUSED_PARAMETER")
    override fun calculate(
        attacker: PokemonState,
        defender: PokemonState,
        move: com.pokemon.battle.model.Move,
        roll: (IntRange) -> Int,
        spreadModifier: Double,
        isCritical: Boolean,
        weather: Weather?,
    ): DamageResult {
        // Simplified gen ignores critical hits
        val isPhysical = move.category == MoveCategory.PHYSICAL
        val atkStat = if (isPhysical) StatType.ATTACK else StatType.SPECIAL_ATTACK
        val defStat = if (isPhysical) StatType.DEFENSE else StatType.SPECIAL_DEFENSE
        val atkStage = if (isPhysical) attacker.statStages.attack else attacker.statStages.specialAttack
        val defStage = if (isPhysical) defender.statStages.defense else defender.statStages.specialDefense

        val atk = (attacker.pokemon.calcStat(atkStat) * stageMultiplier(atkStage)).toInt()
        val def = (defender.pokemon.calcStat(defStat) * stageMultiplier(defStage)).toInt()

        val typeMultiplier = typeChart.effectiveness(move.type, defender.effectiveTypes)
        val effectiveness = Effectiveness.from(typeMultiplier)

        // No STAB, no burn penalty — just power * atk/def * type * spread * roll
        val randomRoll = roll(85..100)
        val damage =
            (move.power.toDouble() * atk / def * typeMultiplier * spreadModifier * randomRoll / 100.0).toInt()
                .coerceAtLeast(if (typeMultiplier > 0.0) 1 else 0)

        return DamageResult(damage, effectiveness)
    }
}
