package com.pokemon.battle.engine

fun interface Phase {
    fun resolve(
        state: BattleState,
        choices: TurnChoices,
    ): PhaseOutput
}
