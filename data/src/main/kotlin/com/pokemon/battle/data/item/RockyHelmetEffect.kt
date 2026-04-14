package com.pokemon.battle.data.item

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.ItemDamage
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.engine.item.ItemEffect
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Slot

/**
 * Rocky Helmet: when the holder takes damage, the attacker loses 1/6 of their max HP.
 * Not consumed — fires every hit until either side faints.
 *
 * FIDELITY NOTE: real Rocky Helmet only fires on *contact* moves (Tackle yes,
 * Earthquake no). We don't model the contact flag on [com.pokemon.battle.model.Move]
 * yet, so v1 fires on any physical or special damage. Matches the simplification
 * pattern Red Card uses. Future: add a `contact: Boolean` to `Move` and gate this
 * effect on it.
 */
object RockyHelmetEffect : ItemEffect {
    override val item = Item.ROCKY_HELMET

    override fun onHolderTookDamage(
        state: BattleState,
        holderSlot: Slot,
        attackerSlot: Slot,
        damageDealt: Int,
        abilities: AbilityRegistry,
    ): List<GameEvent> {
        if (damageDealt <= 0) return emptyList()
        val attacker = state.pokemonFor(attackerSlot)
        if (attacker.isFainted) return emptyList()

        val recoil = (attacker.maxHp / RECOIL_DIVISOR).coerceAtLeast(1)
        val events =
            mutableListOf<GameEvent>(
                ItemDamage(target = attackerSlot, amount = recoil, item = Item.ROCKY_HELMET),
            )
        if (attacker.currentHp <= recoil) events.add(PokemonFainted(attackerSlot))
        return events
    }

    private const val RECOIL_DIVISOR = 6
}
