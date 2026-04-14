package com.pokemon.battle.data.item

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.ItemDamage
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.engine.item.ItemEffect
import com.pokemon.battle.model.Effectiveness
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Slot

/**
 * Rocky Helmet: when the holder takes damage from a **contact** move, the attacker
 * loses 1/6 of their max HP. Not consumed — fires every hit until either side faints.
 *
 * Diary 088 wired the `contact` check through a dedicated seam so attacker-side
 * contact-negating items (Punching Glove, Gen 9) and abilities (Long Reach) suppress
 * this correctly. See [com.pokemon.battle.engine.resolveIsContact].
 */
object RockyHelmetEffect : ItemEffect {
    override val item = Item.ROCKY_HELMET

    override fun onHolderTookDamage(
        state: BattleState,
        holderSlot: Slot,
        attackerSlot: Slot,
        damageDealt: Int,
        effectiveness: Effectiveness,
        contact: Boolean,
        abilities: AbilityRegistry,
    ): List<GameEvent> {
        if (damageDealt <= 0) return emptyList()
        if (!contact) return emptyList()
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
