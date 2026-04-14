package com.pokemon.battle.ai

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.InputRequest
import com.pokemon.battle.engine.InputResponse
import com.pokemon.battle.engine.SwitchTargetRequest
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.loop.ChoiceProvider
import com.pokemon.battle.loop.FaintReplacementProvider
import com.pokemon.battle.loop.InputResponder
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Slot

/**
 * Composes two providers, one per side. Each side's provider only sees choices for
 * its own slots; mid-turn input requests are routed to the side whose slot is in
 * the request.
 */
class SidedAI(
    private val side1: SideProviders,
    private val side2: SideProviders,
) : ChoiceProvider, FaintReplacementProvider, InputResponder {
    override fun getChoices(state: BattleState): TurnChoices {
        val p1 = side1.choiceProvider.getChoices(state)
        val p2 = side2.choiceProvider.getChoices(state)
        return TurnChoices(p1.choices + p2.choices)
    }

    override fun getReplacement(
        state: BattleState,
        faintedSlot: Slot,
    ): Int = sideFor(faintedSlot.side).faintProvider.getReplacement(state, faintedSlot)

    override fun respond(
        state: BattleState,
        request: InputRequest,
    ): InputResponse {
        val side =
            when (request) {
                is SwitchTargetRequest -> request.userSlot.side
            }
        val responder =
            sideFor(side).inputResponder
                ?: error("Side $side received an InputRequest but has no InputResponder wired")
        return responder.respond(state, request)
    }

    private fun sideFor(side: Side): SideProviders = if (side == Side.SIDE_1) side1 else side2
}

/**
 * Bundle of per-side providers. [inputResponder] is optional — sides that never
 * need to answer mid-turn prompts (pure AI that always pre-selects) can omit it.
 */
data class SideProviders(
    val choiceProvider: ChoiceProvider,
    val faintProvider: FaintReplacementProvider,
    val inputResponder: InputResponder? = null,
)
