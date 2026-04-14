package com.pokemon.battle.engine.ability

import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.WeatherSet
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Weather

/** Drizzle: sets rain for 5 turns on switch-in. */
internal object DrizzleEffect : AbilityEffect {
    override val ability = Ability.DRIZZLE

    override fun onSwitchIn(
        state: BattleState,
        slot: Slot,
    ): List<GameEvent> =
        listOf(
            AbilityTriggered(slot, Ability.DRIZZLE),
            WeatherSet(Weather.RAIN, DEFAULT_RAIN_TURNS),
        )

    private const val DEFAULT_RAIN_TURNS = 5
}
