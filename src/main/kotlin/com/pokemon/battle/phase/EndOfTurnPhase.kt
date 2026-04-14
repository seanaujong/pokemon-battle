package com.pokemon.battle.phase

import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.Phase
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.StatusDamage
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.VolatileRemoved
import com.pokemon.battle.engine.WeatherDamage
import com.pokemon.battle.engine.WeatherTick
import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.engine.item.ItemRegistry
import com.pokemon.battle.model.StatusCondition
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.Volatile
import com.pokemon.battle.model.Weather

class EndOfTurnPhase : Phase {
    override fun resolve(
        state: BattleState,
        choices: TurnChoices,
    ): List<BattleEvent> {
        val events = mutableListOf<BattleEvent>()
        var currentState = state

        // Fixed order per game rules: weather → status → items → weather tick
        for (event in weatherDamage(currentState)) {
            events.add(event)
            currentState = event.apply(currentState)
            currentState = checkFaint(event, currentState, events)
        }
        for (event in statusDamage(currentState)) {
            events.add(event)
            currentState = event.apply(currentState)
            currentState = checkFaint(event, currentState, events)
        }
        for (event in itemEffects(currentState)) {
            events.add(event)
            currentState = event.apply(currentState)
        }
        events.addAll(weatherTick(currentState))
        events.addAll(clearProtect(currentState))

        return events
    }

    private fun clearProtect(state: BattleState): List<BattleEvent> =
        state.allSlots().mapNotNull { slot ->
            if (Volatile.Protect in state.pokemonFor(slot).volatiles) {
                VolatileRemoved(slot, Volatile.Protect)
            } else {
                null
            }
        }

    private fun checkFaint(
        event: BattleEvent,
        state: BattleState,
        events: MutableList<BattleEvent>,
    ): BattleState {
        val target =
            when (event) {
                is WeatherDamage -> event.target
                is StatusDamage -> event.target
                else -> return state
            }
        if (state.pokemonFor(target).isFainted) {
            val faintEvent = PokemonFainted(target)
            events.add(faintEvent)
            return faintEvent.apply(state)
        }
        return state
    }

    private fun weatherDamage(state: BattleState): List<BattleEvent> {
        val weather = state.field.weather ?: return emptyList()
        if (weather != Weather.SANDSTORM && weather != Weather.HAIL) return emptyList()

        val immuneTypes =
            when (weather) {
                Weather.SANDSTORM -> setOf(Type.ROCK, Type.GROUND, Type.STEEL)
                Weather.HAIL -> setOf(Type.ICE)
                else -> emptySet()
            }

        return state.allSlots().mapNotNull { slot ->
            val pokemon = state.pokemonFor(slot)
            if (pokemon.isFainted) return@mapNotNull null
            if (pokemon.effectiveTypes.any { it in immuneTypes }) return@mapNotNull null
            if (AbilityRegistry.effectFor(pokemon.ability)?.blocksWeatherDamage(weather) == true) return@mapNotNull null

            val damage = pokemon.maxHp / 16
            WeatherDamage(target = slot, amount = damage, weather = weather)
        }
    }

    private fun statusDamage(state: BattleState): List<BattleEvent> {
        return state.allSlots().mapNotNull { slot ->
            val pokemon = state.pokemonFor(slot)
            if (pokemon.isFainted) return@mapNotNull null

            when (pokemon.status) {
                StatusCondition.BURN -> {
                    val damage = pokemon.maxHp / 16
                    StatusDamage(target = slot, amount = damage, source = StatusCondition.BURN)
                }
                StatusCondition.POISON -> {
                    val damage = pokemon.maxHp / 8
                    StatusDamage(target = slot, amount = damage, source = StatusCondition.POISON)
                }
                else -> null
            }
        }
    }

    private fun itemEffects(state: BattleState): List<BattleEvent> =
        state.allSlots().flatMap { slot ->
            val pokemon = state.pokemonFor(slot)
            if (pokemon.isFainted) return@flatMap emptyList()
            ItemRegistry.effectForHolder(pokemon)?.endOfTurn(pokemon, slot) ?: emptyList()
        }

    private fun weatherTick(state: BattleState): List<BattleEvent> {
        val weather = state.field.weather ?: return emptyList()
        val remaining = state.field.weatherTurnsRemaining - 1
        return listOf(WeatherTick(weather = weather, turnsRemaining = remaining))
    }
}
