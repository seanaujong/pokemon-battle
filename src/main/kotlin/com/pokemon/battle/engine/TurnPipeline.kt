package com.pokemon.battle.engine

import com.pokemon.battle.model.*

import com.pokemon.battle.model.*

class TurnPipeline(private val phases: List<Phase>) {

    data class Result(
        val finalState: BattleState,
        val events: List<BattleEvent>
    )

    fun resolve(initialState: BattleState, choices: TurnChoices): Result {
        return phases.fold(Result(initialState, emptyList())) { (state, events), phase ->
            val newEvents = phase.resolve(state, choices)
            val newState = newEvents.fold(state) { s, event -> event.apply(s) }
            Result(newState, events + newEvents)
        }
    }
}
