package com.pokemon.battle.engine

import com.pokemon.battle.model.Move
import com.pokemon.battle.model.Slot

data class TurnChoices(val choices: Map<Slot, TurnChoice>) {
    fun choiceFor(slot: Slot): TurnChoice? = choices[slot]

    companion object {
        fun singles(
            p1: TurnChoice,
            p2: TurnChoice,
        ) = TurnChoices(mapOf(Slot.p1() to p1, Slot.p2() to p2))
    }
}

sealed interface TurnChoice {
    data class UseMove(
        val move: Move,
        val targetSlot: Slot? = null,
        /** Bench index to send in if the move is a self-switch (U-turn, Volt Switch). */
        val switchTo: Int? = null,
    ) : TurnChoice

    data class Switch(
        val benchIndex: Int,
    ) : TurnChoice
}
