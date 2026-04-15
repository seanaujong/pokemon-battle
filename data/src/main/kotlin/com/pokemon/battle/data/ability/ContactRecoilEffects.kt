package com.pokemon.battle.data.ability

import com.pokemon.battle.engine.AbilityDamage
import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.ability.AbilityEffect
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Slot

/**
 * Contact-recoil abilities: when the holder is hit by a contact move, the attacker
 * loses 1/8 of their max HP. Iron Barbs (Gen V) and Rough Skin (Gen III+) are
 * mechanically identical; they're two enum values so trainers and renderers can
 * distinguish them. Structural mirror of
 * [com.pokemon.battle.data.item.RockyHelmetEffect] on the ability side — 1/8
 * instead of Rocky Helmet's 1/6, and not consumed.
 */
private class ContactRecoil(
    override val ability: Ability,
) : AbilityEffect {
    override fun onHolderTookDamage(
        state: BattleState,
        holderSlot: Slot,
        attackerSlot: Slot,
        damageDealt: Int,
        contact: Boolean,
    ): List<GameEvent> {
        if (damageDealt <= 0) return emptyList()
        if (!contact) return emptyList()
        val attacker = state.pokemonFor(attackerSlot)
        if (attacker.isFainted) return emptyList()

        val recoil = (attacker.maxHp / RECOIL_DIVISOR).coerceAtLeast(1)
        val events =
            mutableListOf<GameEvent>(
                AbilityTriggered(holderSlot, ability),
                AbilityDamage(target = attackerSlot, amount = recoil, ability = ability),
            )
        if (attacker.currentHp <= recoil) events.add(PokemonFainted(attackerSlot))
        return events
    }

    companion object {
        private const val RECOIL_DIVISOR = 8
    }
}

/** Iron Barbs: 1/8 max-HP recoil to attackers on contact. Gen V. */
object IronBarbsEffect : AbilityEffect by ContactRecoil(Ability.IRON_BARBS)

/** Rough Skin: 1/8 max-HP recoil to attackers on contact. Gen III+. */
object RoughSkinEffect : AbilityEffect by ContactRecoil(Ability.ROUGH_SKIN)
