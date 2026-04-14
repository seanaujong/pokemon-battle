package com.pokemon.battle.phase

import com.pokemon.battle.engine.MoveOrderDecided
import com.pokemon.battle.engine.Phase
import com.pokemon.battle.engine.PhaseOutput
import com.pokemon.battle.engine.PipelineState
import com.pokemon.battle.engine.Registries
import com.pokemon.battle.engine.SpeedResolver
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.genVSpeedResolver
import com.pokemon.battle.engine.resolveMoveOrder

class MoveOrderPhase(
    private val registries: Registries = Registries.empty,
    private val speedResolver: SpeedResolver = genVSpeedResolver(registries),
) : Phase {
    override fun resolve(
        state: PipelineState,
        choices: TurnChoices,
    ): PhaseOutput {
        val result = resolveMoveOrder(state.battle, choices, speedResolver)
        return PhaseOutput.Completed(listOf(MoveOrderDecided(result.order, result.leadReason)))
    }
}
