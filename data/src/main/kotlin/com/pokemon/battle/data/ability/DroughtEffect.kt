package com.pokemon.battle.data.ability

import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.WeatherSet
import com.pokemon.battle.engine.ability.AbilityEffect
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Weather

/** Drought: sets sun for 5 turns on switch-in. Mirrors [DrizzleEffect]. */
object DroughtEffect : AbilityEffect {
    override val ability = Ability.DROUGHT

    override fun onSwitchIn(
        state: BattleState,
        slot: Slot,
    ): List<GameEvent> =
        listOf(
            AbilityTriggered(slot, Ability.DROUGHT),
            WeatherSet(Weather.SUN, DEFAULT_SUN_TURNS),
        )

    private const val DEFAULT_SUN_TURNS = 5
}
