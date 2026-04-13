package com.pokemon.battle.engine

import com.pokemon.battle.model.*

data class TurnChoices(val choices: Map<Slot, TurnChoice>) {
    fun choiceFor(slot: Slot): TurnChoice? = choices[slot]

    companion object {
        fun singles(p1: TurnChoice, p2: TurnChoice) =
            TurnChoices(mapOf(Slot.p1() to p1, Slot.p2() to p2))
    }
}

sealed interface TurnChoice {
    data class UseMove(val move: Move) : TurnChoice
}
