package com.pokemon.battle.phase

import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*

class MoveExecutionPhase(
    private val roll: (IntRange) -> Int = { range -> range.random() },
    private val chanceCheck: ChanceCheck = defaultChanceCheck
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

            val newEvents = checkStatusThenExecute(currentState, player, choice.move)
            for (event in newEvents) {
                events.add(event)
                currentState = event.apply(currentState)
            }
        }

        return events
    }

    private fun checkStatusThenExecute(state: BattleState, player: Player, move: Move): List<BattleEvent> {
        val attacker = state.pokemonFor(player)

        // Sleep check: decrement counter, fail if still asleep
        if (attacker.status == StatusCondition.SLEEP) {
            val sleepVolatile = attacker.volatiles.filterIsInstance<Volatile.Sleep>().firstOrNull()
            if (sleepVolatile != null) {
                val remaining = sleepVolatile.turnsRemaining - 1
                if (remaining > 0) {
                    return listOf(
                        VolatileChanged(player, sleepVolatile, Volatile.Sleep(remaining)),
                        MoveFailed(player, FailReason.ASLEEP)
                    )
                } else {
                    val cleared = StatusCleared(player, StatusCondition.SLEEP)
                    return listOf(cleared) + executeMove(cleared.apply(state), player, move)
                }
            }
        }

        // Freeze check: per-turn 20% thaw chance
        if (attacker.status == StatusCondition.FREEZE) {
            if (chanceCheck(20, FailReason.FROZEN)) {
                val cleared = StatusCleared(player, StatusCondition.FREEZE)
                return listOf(cleared) + executeMove(cleared.apply(state), player, move)
            } else {
                return listOf(MoveFailed(player, FailReason.FROZEN))
            }
        }

        // Paralysis check: 25% chance to skip
        if (attacker.status == StatusCondition.PARALYSIS) {
            if (chanceCheck(25, FailReason.FULLY_PARALYZED)) {
                return listOf(MoveFailed(player, FailReason.FULLY_PARALYZED))
            }
        }

        return executeMove(state, player, move)
    }

    private fun executeMove(state: BattleState, player: Player, move: Move): List<BattleEvent> {
        val events = mutableListOf<BattleEvent>(MoveAttempted(player, move))

        // Damage phase: if the move targets the opponent and has power, calculate damage
        if (move.target == MoveTarget.OPPONENT && move.power > 0) {
            val attacker = state.pokemonFor(player)
            val defender = state.pokemonFor(player.opponent())

            if (!defender.isFainted) {
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
            }
        }

        // Effects phase: process move effects (stat boosts, etc.)
        // Skip opponent-targeting effects if the opponent fainted from damage
        val opponentFainted = events.any { it is PokemonFainted }
        for (effect in move.effects) {
            val effectTarget = when (move.target) {
                MoveTarget.SELF -> player
                MoveTarget.OPPONENT -> player.opponent()
            }
            if (effectTarget == player.opponent() && opponentFainted) continue
            events.addAll(resolveEffect(effect, player, move))
        }

        return events
    }

    private fun resolveEffect(effect: MoveEffect, player: Player, move: Move): List<BattleEvent> {
        return when (effect) {
            is MoveEffect.StatBoost -> {
                val target = when (move.target) {
                    MoveTarget.SELF -> player
                    MoveTarget.OPPONENT -> player.opponent()
                }
                listOf(StatChanged(target, effect.stat, effect.stages))
            }
        }
    }
}
