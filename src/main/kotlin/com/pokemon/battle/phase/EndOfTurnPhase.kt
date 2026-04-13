package com.pokemon.battle.phase

import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*

class EndOfTurnPhase : Phase {
    override fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent> {
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

        return events
    }

    private fun checkFaint(event: BattleEvent, state: BattleState, events: MutableList<BattleEvent>): BattleState {
        val target = when (event) {
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

        val immuneTypes = when (weather) {
            Weather.SANDSTORM -> setOf(Type.ROCK, Type.GROUND, Type.STEEL)
            Weather.HAIL -> setOf(Type.ICE)
            else -> emptySet()
        }

        return state.allSlots().mapNotNull { slot ->
            val pokemon = state.pokemonFor(slot)
            if (pokemon.isFainted) return@mapNotNull null
            if (pokemon.pokemon.species.types.any { it in immuneTypes }) return@mapNotNull null
            if (isWeatherImmune(pokemon.ability, weather)) return@mapNotNull null

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

    private fun itemEffects(state: BattleState): List<BattleEvent> {
        return state.allSlots().mapNotNull { slot ->
            val pokemon = state.pokemonFor(slot)
            if (pokemon.isFainted) return@mapNotNull null

            when (pokemon.item) {
                Item.LEFTOVERS -> {
                    if (pokemon.currentHp < pokemon.maxHp) {
                        val healing = pokemon.maxHp / 16
                        ItemHealing(target = slot, amount = healing, item = Item.LEFTOVERS)
                    } else null
                }
                else -> null
            }
        }
    }

    private fun weatherTick(state: BattleState): List<BattleEvent> {
        val weather = state.field.weather ?: return emptyList()
        val remaining = state.field.weatherTurnsRemaining - 1
        return listOf(WeatherTick(weather = weather, turnsRemaining = remaining))
    }
}
