package com.pokemon.battle.engine

import com.pokemon.battle.model.*

import com.pokemon.battle.model.*

data class TurnChoices(
    val p1: TurnChoice,
    val p2: TurnChoice
) {
    fun choiceFor(player: Player): TurnChoice = when (player) {
        Player.P1 -> p1
        Player.P2 -> p2
    }
}

sealed interface TurnChoice {
    data class UseMove(val move: Move) : TurnChoice
}
