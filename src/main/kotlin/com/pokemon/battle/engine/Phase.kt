package com.pokemon.battle.engine

import com.pokemon.battle.model.*

fun interface Phase {
    fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent>
}
