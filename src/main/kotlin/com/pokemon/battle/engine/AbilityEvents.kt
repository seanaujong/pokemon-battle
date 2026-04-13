package com.pokemon.battle.engine

import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Slot

data class AbilityTriggered(
    val slot: Slot,
    val ability: Ability,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state
}

data class AbilityBlocked(
    val slot: Slot,
    val ability: Ability,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state
}
