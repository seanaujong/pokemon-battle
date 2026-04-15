package com.pokemon.battle.engine

import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Slot
import kotlinx.serialization.Serializable

@Serializable
data class AbilityTriggered(
    val slot: Slot,
    val ability: Ability,
) : GameEvent {
    override fun apply(state: BattleState): BattleState = state
}

@Serializable
data class AbilityBlocked(
    val slot: Slot,
    val ability: Ability,
) : GameEvent {
    override fun apply(state: BattleState): BattleState = state
}

/**
 * HP loss caused by an opponent's ability (Iron Barbs / Rough Skin contact recoil).
 * Mirrors [com.pokemon.battle.engine.ItemDamage] on the ability side.
 */
@Serializable
data class AbilityDamage(
    val target: Slot,
    val amount: Int,
    val ability: Ability,
) : GameEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp - amount).coerceAtLeast(0)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}
