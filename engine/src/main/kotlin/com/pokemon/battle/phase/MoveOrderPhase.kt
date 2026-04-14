package com.pokemon.battle.phase

import com.pokemon.battle.engine.MoveOrderDecided
import com.pokemon.battle.engine.Phase
import com.pokemon.battle.engine.PhaseOutput
import com.pokemon.battle.engine.PipelineState
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.resolveMoveOrder

class MoveOrderPhase : Phase {
    override fun resolve(
        state: PipelineState,
        choices: TurnChoices,
    ): PhaseOutput {
        val result = resolveMoveOrder(state.battle, choices)
        return PhaseOutput.Completed(listOf(MoveOrderDecided(result.order, result.leadReason)))
    }
}
