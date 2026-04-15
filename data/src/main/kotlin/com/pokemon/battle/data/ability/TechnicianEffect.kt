package com.pokemon.battle.data.ability

import com.pokemon.battle.engine.ability.AbilityEffect
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.PokemonState

/**
 * Technician: the holder's moves with base power ≤ 60 deal 1.5x damage. Plain
 * attacker-side damage multiplier — same shape as
 * [com.pokemon.battle.data.ability.BlazeEffect] (pinch-type boost) but gated on
 * [Move.power] rather than attacker HP.
 *
 * Gen IV+ (introduced Gen IV).
 */
object TechnicianEffect : AbilityEffect {
    override val ability = Ability.TECHNICIAN

    override fun attackerDamageModifier(
        attacker: PokemonState,
        move: Move,
    ): Double = if (move.power in 1..TECHNICIAN_POWER_CAP) TECHNICIAN_MULTIPLIER else 1.0

    private const val TECHNICIAN_POWER_CAP = 60
    private const val TECHNICIAN_MULTIPLIER = 1.5
}
