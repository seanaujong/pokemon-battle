package com.pokemon.battle

class EndOfTurnPhase : Phase {
    override fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent> {
        // Minimal implementation — expand as we add weather, status damage, items
        return emptyList()
    }
}
