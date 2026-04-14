package com.pokemon.battle.engine

import com.pokemon.battle.model.Side
import com.pokemon.battle.model.SideCondition

/** A new side condition is established (e.g. Tailwind set by a move). */
data class SideConditionSet(
    val side: Side,
    val condition: SideCondition,
    val turnsRemaining: Int,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state.withSideCondition(side, condition, turnsRemaining)
}

/** A side condition's counter ticks down. */
data class SideConditionTick(
    val side: Side,
    val condition: SideCondition,
    val turnsRemaining: Int,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state.withSideCondition(side, condition, turnsRemaining)
}

/** A side condition expires (counter hit 0). */
data class SideConditionExpired(
    val side: Side,
    val condition: SideCondition,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state.withSideCondition(side, condition, 0)
}
