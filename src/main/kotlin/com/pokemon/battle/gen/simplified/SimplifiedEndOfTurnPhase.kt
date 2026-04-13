package com.pokemon.battle.gen.simplified

import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ItemHealing
import com.pokemon.battle.engine.Phase
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.StatusDamage
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.WeatherTick
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.StatusCondition

/**
 * Simplified end-of-turn: burn does 1/8 max HP (double Gen V), no weather damage.
 * Weather still ticks down but doesn't deal damage.
 */
class SimplifiedEndOfTurnPhase : Phase {
    override fun resolve(
        state: BattleState,
        choices: TurnChoices,
    ): List<BattleEvent> {
        val events = mutableListOf<BattleEvent>()
        var currentState = state

        // No weather damage — skipped entirely

        // Status damage: burn does 1/8 (not 1/16)
        for (event in statusDamage(currentState)) {
            events.add(event)
            currentState = event.apply(currentState)
            currentState = checkFaint(event, currentState, events)
        }

        // Item effects (same as Gen V)
        for (event in itemEffects(currentState)) {
            events.add(event)
            currentState = event.apply(currentState)
        }

        // Weather tick (countdown only, no damage)
        events.addAll(weatherTick(currentState))

        return events
    }

    private fun checkFaint(
        event: BattleEvent,
        state: BattleState,
        events: MutableList<BattleEvent>,
    ): BattleState {
        val target = (event as? StatusDamage)?.target ?: return state
        if (state.pokemonFor(target).isFainted) {
            val faintEvent = PokemonFainted(target)
            events.add(faintEvent)
            return faintEvent.apply(state)
        }
        return state
    }

    @Suppress("ReturnCount")
    private fun statusDamage(state: BattleState): List<BattleEvent> =
        state.allSlots().mapNotNull { slot ->
            val pokemon = state.pokemonFor(slot)
            if (pokemon.isFainted) return@mapNotNull null

            when (pokemon.status) {
                // Burn does 1/8 max HP in simplified gen (double Gen V's 1/16)
                StatusCondition.BURN -> StatusDamage(target = slot, amount = pokemon.maxHp / 8, source = StatusCondition.BURN)
                StatusCondition.POISON -> StatusDamage(target = slot, amount = pokemon.maxHp / 8, source = StatusCondition.POISON)
                else -> null
            }
        }

    private fun itemEffects(state: BattleState): List<BattleEvent> =
        state.allSlots().mapNotNull { slot ->
            val pokemon = state.pokemonFor(slot)
            if (pokemon.isFainted) return@mapNotNull null

            when (pokemon.item) {
                Item.LEFTOVERS -> {
                    if (pokemon.currentHp < pokemon.maxHp) {
                        ItemHealing(target = slot, amount = pokemon.maxHp / 16, item = Item.LEFTOVERS)
                    } else {
                        null
                    }
                }
                else -> null
            }
        }

    private fun weatherTick(state: BattleState): List<BattleEvent> {
        val weather = state.field.weather ?: return emptyList()
        val remaining = state.field.weatherTurnsRemaining - 1
        return listOf(WeatherTick(weather = weather, turnsRemaining = remaining))
    }
}
