package com.pokemon.battle.engine

import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.engine.item.ItemRegistry
import com.pokemon.battle.model.Effectiveness
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.StatType
import com.pokemon.battle.model.StatusCondition
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.Weather
import com.pokemon.battle.model.stageMultiplier

data class DamageResult(val damage: Int, val effectiveness: Effectiveness)

fun interface DamageCalculator {
    @Suppress("LongParameterList") // Core damage calc — each param is a distinct modifier
    fun calculate(
        attacker: PokemonState,
        defender: PokemonState,
        move: Move,
        roll: (IntRange) -> Int,
        spreadModifier: Double,
        isCritical: Boolean,
        weather: Weather?,
    ): DamageResult
}

/**
 * Standard Gen V+ damage formula with IVs/EVs/Nature.
 * Gen-specific rules: burn penalty (0.5x physical), STAB (1.5x), crit (1.5x + ignore stages).
 */
class GenVDamageCalculator(
    private val typeChart: TypeChart = StandardTypeChart,
) : DamageCalculator {
    @Suppress("CyclomaticComplexMethod") // Single-expression damage formula with many independent modifiers
    override fun calculate(
        attacker: PokemonState,
        defender: PokemonState,
        move: Move,
        roll: (IntRange) -> Int,
        spreadModifier: Double,
        isCritical: Boolean,
        weather: Weather?,
    ): DamageResult {
        val level = attacker.pokemon.level

        val isPhysical = move.category == MoveCategory.PHYSICAL
        val atkStat = if (isPhysical) StatType.ATTACK else StatType.SPECIAL_ATTACK
        val defStat = if (isPhysical) StatType.DEFENSE else StatType.SPECIAL_DEFENSE
        val atkStage = if (isPhysical) attacker.statStages.attack else attacker.statStages.specialAttack
        val defStage = if (isPhysical) defender.statStages.defense else defender.statStages.specialDefense

        // Crits: ignore attacker's negative stages and defender's positive stages
        val effectiveAtkStage = if (isCritical) maxOf(atkStage, 0) else atkStage
        val effectiveDefStage = if (isCritical) minOf(defStage, 0) else defStage

        val atk = (attacker.pokemon.calcStat(atkStat) * stageMultiplier(effectiveAtkStage)).toInt()
        val def = (defender.pokemon.calcStat(defStat) * stageMultiplier(effectiveDefStage)).toInt()

        val burnMod = if (attacker.status == StatusCondition.BURN && isPhysical) 0.5 else 1.0
        val critMod = if (isCritical) 1.5 else 1.0
        val weatherMod = weatherDamageModifier(weather, move.type)
        val attackerItemMod = ItemRegistry.effectForHolder(attacker)?.attackerDamageModifier(attacker, move) ?: 1.0
        val defenderItemMod = ItemRegistry.effectForHolder(defender)?.defenderDamageModifier(defender, move) ?: 1.0
        val attackerAbilityMod = AbilityRegistry.effectFor(attacker.effectiveAbility)?.attackerDamageModifier(attacker, move) ?: 1.0
        val defenderAbilityMod = AbilityRegistry.effectFor(defender.effectiveAbility)?.defenderDamageModifier(defender, move) ?: 1.0
        val itemMod = attackerItemMod * defenderItemMod
        val abilityMod = attackerAbilityMod * defenderAbilityMod

        val typeMultiplier = typeChart.effectiveness(move.type, defender.effectiveTypes)
        val effectiveness = Effectiveness.from(typeMultiplier)

        val stab = if (move.type in attacker.effectiveTypes) 1.5 else 1.0

        val randomRoll = roll(85..100)

        val baseDamage = ((2.0 * level / 5.0 + 2.0) * move.power * atk / def) / 50.0 + 2.0
        val modifier = stab * typeMultiplier * burnMod * critMod * weatherMod * itemMod * abilityMod * spreadModifier
        val damage =
            (baseDamage * modifier * randomRoll / 100.0).toInt()
                .coerceAtLeast(if (typeMultiplier > 0.0) 1 else 0)

        return DamageResult(damage, effectiveness)
    }
}

/**
 * Gen V+ weather damage modifier. Rain boosts Water and weakens Fire; Sun inverts.
 * Hail and Sandstorm don't modify move damage. Returns 1.0 when there's no relevant weather.
 */
internal fun weatherDamageModifier(
    weather: Weather?,
    moveType: Type,
): Double =
    when (weather) {
        Weather.RAIN ->
            when (moveType) {
                Type.WATER -> 1.5
                Type.FIRE -> 0.5
                else -> 1.0
            }
        Weather.SUN ->
            when (moveType) {
                Type.FIRE -> 1.5
                Type.WATER -> 0.5
                else -> 1.0
            }
        else -> 1.0
    }

/** Convenience function using the default Gen V+ calculator with standard type chart. */
@Suppress("LongParameterList") // Mirrors DamageCalculator.calculate with defaults
fun calculateDamage(
    attacker: PokemonState,
    defender: PokemonState,
    move: Move,
    roll: (IntRange) -> Int = { range -> range.random() },
    spreadModifier: Double = 1.0,
    isCritical: Boolean = false,
    weather: Weather? = null,
): DamageResult = GenVDamageCalculator().calculate(attacker, defender, move, roll, spreadModifier, isCritical, weather)
