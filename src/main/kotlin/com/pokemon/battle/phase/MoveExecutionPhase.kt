package com.pokemon.battle.phase

import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*

class MoveExecutionPhase(
    private val roll: (IntRange) -> Int = { range -> range.random() }
) : Phase {
    override fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent> {
        val order = resolveMoveOrder(state, choices)
        val players = listOf(order.first, order.first.opponent())
        val events = mutableListOf<BattleEvent>()
        var currentState = state

        for (player in players) {
            val choice = choices.choiceFor(player)
            if (choice !is TurnChoice.UseMove) continue

            val attacker = currentState.pokemonFor(player)
            if (attacker.isFainted) continue

            val newEvents = executeMove(currentState, player, choice.move)
            for (event in newEvents) {
                events.add(event)
                currentState = event.apply(currentState)
            }
        }

        return events
    }

    private fun executeMove(state: BattleState, player: Player, move: Move): List<BattleEvent> {
        val attacker = state.pokemonFor(player)
        val defender = state.pokemonFor(player.opponent())

        val events = mutableListOf<BattleEvent>(MoveAttempted(player, move))

        if (defender.isFainted) return events

        val result = calculateDamage(attacker, defender, move, roll)
        val damageEvent = DamageDealt(
            target = player.opponent(),
            amount = result.damage,
            effectiveness = result.effectiveness,
            critical = false // TODO: critical hit logic
        )
        events.add(damageEvent)

        val stateAfterDamage = damageEvent.apply(state)
        if (stateAfterDamage.pokemonFor(player.opponent()).isFainted) {
            events.add(PokemonFainted(player.opponent()))
        }

        return events
    }
}
