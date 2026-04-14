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
