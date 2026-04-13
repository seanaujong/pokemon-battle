package com.pokemon.battle.engine

import com.pokemon.battle.model.*

data class MoveOrderResult(val first: Player, val reason: OrderReason)

fun resolveMoveOrder(state: BattleState, choices: TurnChoices): MoveOrderResult {
    val p1Priority = (choices.p1 as? TurnChoice.UseMove)?.move?.priority ?: 0
    val p2Priority = (choices.p2 as? TurnChoice.UseMove)?.move?.priority ?: 0

    return when {
        p1Priority > p2Priority -> MoveOrderResult(Player.P1, OrderReason.PRIORITY)
        p2Priority > p1Priority -> MoveOrderResult(Player.P2, OrderReason.PRIORITY)
        else -> {
            val speed1 = state.pokemon1.effectiveSpeed()
            val speed2 = state.pokemon2.effectiveSpeed()
            when {
                speed1 > speed2 -> MoveOrderResult(Player.P1, OrderReason.SPEED)
                speed2 > speed1 -> MoveOrderResult(Player.P2, OrderReason.SPEED)
                else -> MoveOrderResult(Player.P1, OrderReason.SPEED_TIE) // TODO: random tie-break
            }
        }
    }
}
