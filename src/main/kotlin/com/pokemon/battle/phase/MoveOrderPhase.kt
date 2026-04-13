package com.pokemon.battle.phase

import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.MoveOrderDecided
import com.pokemon.battle.engine.Phase
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.resolveMoveOrder

class MoveOrderPhase : Phase {
    override fun resolve(
        state: BattleState,
        choices: TurnChoices,
    ): List<BattleEvent> {
        val result = resolveMoveOrder(state, choices)
        return listOf(MoveOrderDecided(result.order, result.leadReason))
    }
}
