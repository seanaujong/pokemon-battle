package com.pokemon.battle.engine

import com.pokemon.battle.model.*

/**
 * Standard Gen V+ damage formula with IVs/EVs/Nature.
 *
 * [roll] controls the random factor (85..100 in real games). Pass a fixed lambda for testing.
 */
fun calculateDamage(
    attacker: PokemonState,
    defender: PokemonState,
    move: Move,
    roll: (IntRange) -> Int = { range -> range.random() },
    spreadModifier: Double = 1.0
): DamageResult {
    val level = attacker.pokemon.level

    val isPhysical = move.category == MoveCategory.PHYSICAL
    val atkStat = if (isPhysical) StatType.ATTACK else StatType.SPECIAL_ATTACK
    val defStat = if (isPhysical) StatType.DEFENSE else StatType.SPECIAL_DEFENSE
    val atkStage = if (isPhysical) attacker.statStages.attack else attacker.statStages.specialAttack
    val defStage = if (isPhysical) defender.statStages.defense else defender.statStages.specialDefense

    val atk = (attacker.pokemon.calcStat(atkStat) * stageMultiplier(atkStage)).toInt()
    val def = (defender.pokemon.calcStat(defStat) * stageMultiplier(defStage)).toInt()

    // Burn halves physical attack damage
    val burnMod = if (attacker.status == StatusCondition.BURN && isPhysical) 0.5 else 1.0

    val typeMultiplier = typeEffectiveness(move.type, defender.pokemon.species.types)
    val effectiveness = Effectiveness.from(typeMultiplier)

    // STAB: 1.5x if the move type matches one of the attacker's types
    val stab = if (move.type in attacker.pokemon.species.types) 1.5 else 1.0

    val randomRoll = roll(85..100)

    // Standard formula: ((2*level/5 + 2) * power * atk/def) / 50 + 2, then modifiers
    val baseDamage = ((2.0 * level / 5.0 + 2.0) * move.power * atk / def) / 50.0 + 2.0
    val damage = (baseDamage * stab * typeMultiplier * burnMod * spreadModifier * randomRoll / 100.0).toInt()
        .coerceAtLeast(if (typeMultiplier > 0.0) 1 else 0)

    return DamageResult(damage, effectiveness)
}

data class DamageResult(val damage: Int, val effectiveness: Effectiveness)
