package com.pokemon.battle.ai

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.loop.ChoiceProvider
import com.pokemon.battle.loop.FaintReplacementProvider
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Slot

/**
 * Composes two ChoiceProviders/FaintReplacementProviders, one per side.
 * Each side's AI only sees choices for its own slots.
 */
class SidedAI(
    private val side1: Pair<ChoiceProvider, FaintReplacementProvider>,
    private val side2: Pair<ChoiceProvider, FaintReplacementProvider>,
) : ChoiceProvider, FaintReplacementProvider {
    override fun getChoices(state: BattleState): TurnChoices {
        val p1 = side1.first.getChoices(state)
        val p2 = side2.first.getChoices(state)
        return TurnChoices(p1.choices + p2.choices)
    }

    override fun getReplacement(
        state: BattleState,
        faintedSlot: Slot,
    ): Int {
        return if (faintedSlot.side == Side.SIDE_1) {
            side1.second.getReplacement(state, faintedSlot)
        } else {
            side2.second.getReplacement(state, faintedSlot)
        }
    }
}
