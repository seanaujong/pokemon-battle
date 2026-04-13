package com.pokemon.battle

class MoveOrderPhase : Phase {
    override fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent> {
        val result = resolveMoveOrder(state, choices)
        return listOf(MoveOrderDecided(result.first, result.reason))
    }
}
