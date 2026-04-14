package com.pokemon.battle.engine

import com.pokemon.battle.model.Effectiveness
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.StatType
import com.pokemon.battle.model.StatusCondition
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.Weather
import com.pokemon.battle.model.stageMultiplier

/**
 * Gen III (Ruby/Sapphire/Emerald, FR/LG) damage calculator.
 *
 * The one mainline-mechanical difference from [GenVDamageCalculator] we're
 * modelling: in Gen I–III, physical/special is determined by **move type**, not
 * by a per-move [com.pokemon.battle.model.MoveCategory] field. Shadow Ball is
 * Special→Physical, Sludge Bomb is Special→Physical, etc. From Gen IV onward,
 * category is a per-move property; our [com.pokemon.battle.model.Move.category]
 * field matches that later convention, so here we override with type-derived
 * classification.
 *
 * Other Gen III mechanics we do NOT yet differentiate from Gen V (pragmatic
 * scope — matrix team pool doesn't exercise these):
 * - Crit formula (Gen III uses speed-based Focus Energy etc.; we use a flat
 *   multiplier regardless).
 * - Burn atk drop (same 0.5× in Gen III and V — no delta).
 * - Weather mechanics (broadly the same for rain/sun).
 *
 * If the matrix surfaces a behavioural delta beyond the P/S split, that's a
 * signal to deepen the model — same forcing-function discipline as diary 087.
 */
internal class GenIIIDamageCalculator(
    private val registries: Registries = Registries.empty,
    private val typeChart: TypeChart = StandardTypeChart,
) : DamageCalculator {
    @Suppress("CyclomaticComplexMethod") // Parallel shape to GenVDamageCalculator; single-expression formula.
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

        // Gen III: physical/special split is determined by move type, not move.category.
        val isPhysical = isPhysicalType(move.type)
        val atkStat = if (isPhysical) StatType.ATTACK else StatType.SPECIAL_ATTACK
        val defStat = if (isPhysical) StatType.DEFENSE else StatType.SPECIAL_DEFENSE
        val atkStage = if (isPhysical) attacker.statStages.attack else attacker.statStages.specialAttack
        val defStage = if (isPhysical) defender.statStages.defense else defender.statStages.specialDefense

        val effectiveAtkStage = if (isCritical) maxOf(atkStage, 0) else atkStage
        val effectiveDefStage = if (isCritical) minOf(defStage, 0) else defStage

        val atk = (attacker.pokemon.calcStat(atkStat) * stageMultiplier(effectiveAtkStage)).toInt()
        val def = (defender.pokemon.calcStat(defStat) * stageMultiplier(effectiveDefStage)).toInt()

        val burnMod = if (attacker.status == StatusCondition.BURN && isPhysical) 0.5 else 1.0
        val critMod = if (isCritical) 1.5 else 1.0
        val weatherMod = weatherDamageModifier(weather, move.type)
        val attackerItemMod = registries.items.effectForHolder(attacker)?.attackerDamageModifier(attacker, move) ?: 1.0
        val defenderItemMod = registries.items.effectForHolder(defender)?.defenderDamageModifier(defender, move) ?: 1.0
        val attackerAbilityMod = registries.abilities.effectFor(attacker.effectiveAbility)?.attackerDamageModifier(attacker, move) ?: 1.0
        val defenderAbilityMod = registries.abilities.effectFor(defender.effectiveAbility)?.defenderDamageModifier(defender, move) ?: 1.0
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
 * Gen I–III physical/special split: classification by type. "Physical" types are
 * the nine listed below; everything else is Special. This is the defining rule
 * that Gen IV replaced with a per-move category field.
 */
internal fun isPhysicalType(type: Type): Boolean =
    when (type) {
        Type.NORMAL, Type.FIGHTING, Type.POISON, Type.GROUND,
        Type.FLYING, Type.BUG, Type.ROCK, Type.GHOST, Type.STEEL,
        -> true
        else -> false
    }
