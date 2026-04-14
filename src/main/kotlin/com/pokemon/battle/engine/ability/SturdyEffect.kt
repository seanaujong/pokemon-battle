package com.pokemon.battle.engine.ability

import com.pokemon.battle.engine.DamageAdjustment
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.PokemonState

/**
 * Sturdy: if the holder is at full HP and would be KO'd, survive at 1 HP. Not consumed
 * (it's an ability, not an item). Structurally identical to Focus Sash but permanent.
 */
object SturdyEffect : AbilityEffect {
    override val ability = Ability.STURDY

    override fun interceptIncomingDamage(
        defender: PokemonState,
        rawDamage: Int,
    ): DamageAdjustment? {
        val atFullHp = defender.currentHp == defender.maxHp
        val wouldKo = rawDamage >= defender.currentHp
        if (!atFullHp || !wouldKo) return null
        return DamageAdjustment(adjustedDamage = defender.currentHp - 1, consumed = false)
    }

    override fun renderTriggered(pokemonName: String): String = "$pokemonName endured the hit with Sturdy!"
}
