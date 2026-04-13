package com.pokemon.battle.phase

import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*

class MoveOrderPhase : Phase {
    override fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent> {
        val result = resolveMoveOrder(state, choices)
        return listOf(MoveOrderDecided(result.order, result.leadReason))
    }
}
