package com.pokemon.battle.engine

import com.pokemon.battle.model.*

data class AbilityTriggered(
    val slot: Slot,
    val ability: Ability
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state
}

data class AbilityBlocked(
    val slot: Slot,
    val ability: Ability
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state
}
