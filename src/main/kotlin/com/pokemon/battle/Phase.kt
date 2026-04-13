package com.pokemon.battle

fun interface Phase {
    fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent>
}
