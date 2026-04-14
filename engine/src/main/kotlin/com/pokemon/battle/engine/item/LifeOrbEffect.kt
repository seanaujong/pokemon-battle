package com.pokemon.battle.engine.item

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.ItemDamage
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot

/**
 * Life Orb: boosts the holder's damage by 1.3x and deals 10% max HP recoil after any move
 * that landed damage. Persistent (not consumed).
 */
internal object LifeOrbEffect : ItemEffect {
    private const val DAMAGE_MULTIPLIER = 1.3
    private const val RECOIL_FRACTION = 10

    override val item = Item.LIFE_ORB

    override fun attackerDamageModifier(
        attacker: PokemonState,
        move: Move,
    ): Double = DAMAGE_MULTIPLIER

    override fun afterUserMoveDamage(
        state: BattleState,
        userSlot: Slot,
        move: Move,
        damageLanded: Boolean,
    ): List<GameEvent> {
        val user = state.pokemonFor(userSlot)
        if (!damageLanded || user.isFainted) return emptyList()
        val recoilAmount = user.maxHp / RECOIL_FRACTION
        val recoil = ItemDamage(userSlot, recoilAmount, Item.LIFE_ORB)
        val willFaint = user.currentHp <= recoilAmount
        return if (willFaint) listOf(recoil, PokemonFainted(userSlot)) else listOf(recoil)
    }
}
