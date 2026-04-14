package com.pokemon.battle.engine.ability

import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Type

/**
 * Pinch-type boost abilities: at or below 1/3 max HP, damaging moves of the
 * matching type are boosted by 1.5x.
 *
 * Gen 4-5: activated only at or below 1/3 HP.
 * Gen 6+: same threshold, same multiplier.
 * (Gen 3 doesn't have Life-Orb-style multiplicative boost here; same formula.)
 */
private class PinchTypeBoost(
    override val ability: Ability,
    private val boostedType: Type,
) : AbilityEffect {
    override fun attackerDamageModifier(
        attacker: PokemonState,
        move: Move,
    ): Double {
        if (move.type != boostedType) return 1.0
        if (attacker.currentHp * PINCH_DENOMINATOR > attacker.maxHp) return 1.0
        return PINCH_MULTIPLIER
    }

    companion object {
        private const val PINCH_MULTIPLIER = 1.5
        private const val PINCH_DENOMINATOR = 3
    }
}

internal object BlazeEffect : AbilityEffect by PinchTypeBoost(Ability.BLAZE, Type.FIRE)

internal object OvergrowEffect : AbilityEffect by PinchTypeBoost(Ability.OVERGROW, Type.GRASS)

internal object TorrentEffect : AbilityEffect by PinchTypeBoost(Ability.TORRENT, Type.WATER)
