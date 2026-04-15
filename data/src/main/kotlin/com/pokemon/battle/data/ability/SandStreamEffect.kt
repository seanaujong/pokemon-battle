package com.pokemon.battle.data.ability

import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.WeatherSet
import com.pokemon.battle.engine.ability.AbilityEffect
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Weather

/**
 * Sand Stream: sets sandstorm for 5 turns on switch-in. Mirrors [DrizzleEffect] /
 * [DroughtEffect]. Gen V+ duration (in Gen III–IV Sand Stream was permanent;
 * we model Gen V onward).
 */
object SandStreamEffect : AbilityEffect {
    override val ability = Ability.SAND_STREAM

    override fun onSwitchIn(
        state: BattleState,
        slot: Slot,
    ): List<GameEvent> =
        listOf(
            AbilityTriggered(slot, Ability.SAND_STREAM),
            WeatherSet(Weather.SANDSTORM, DEFAULT_SANDSTORM_TURNS),
        )

    private const val DEFAULT_SANDSTORM_TURNS = 5
}
