package com.pokemon.battle.data.ability

import com.pokemon.battle.engine.ability.AbilityEffect
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Type

/** Levitate: grants immunity to damaging Ground-type moves. */
object LevitateEffect : AbilityEffect {
    override val ability = Ability.LEVITATE

    override fun blocksMove(
        defender: PokemonState,
        move: Move,
    ): Boolean = move.type == Type.GROUND && move.power > 0
}
