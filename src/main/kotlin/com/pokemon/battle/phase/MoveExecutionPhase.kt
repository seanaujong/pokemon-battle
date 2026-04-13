package com.pokemon.battle.phase

import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*

class MoveExecutionPhase(
    private val roll: (IntRange) -> Int = { range -> range.random() },
    private val chanceCheck: ChanceCheck = defaultChanceCheck
) : Phase {
    override fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent> {
        val order = resolveMoveOrder(state, choices).order
        val events = mutableListOf<BattleEvent>()
        var currentState = state

        for (slot in order) {
            val choice = choices.choiceFor(slot)
            if (choice !is TurnChoice.UseMove) continue

            val attacker = currentState.pokemonFor(slot)
            if (attacker.isFainted) continue

            val newEvents = checkStatusThenExecute(currentState, slot, choice)
            for (event in newEvents) {
                events.add(event)
                currentState = event.apply(currentState)
            }
        }

        return events
    }

    private fun checkStatusThenExecute(state: BattleState, slot: Slot, choice: TurnChoice.UseMove): List<BattleEvent> {
        val attacker = state.pokemonFor(slot)

        if (attacker.status == StatusCondition.SLEEP) {
            val sleepVolatile = attacker.volatiles.filterIsInstance<Volatile.Sleep>().firstOrNull()
            if (sleepVolatile == null) {
                // Inconsistent state: status is SLEEP but no Volatile.Sleep — clear and act
                val cleared = StatusCleared(slot, StatusCondition.SLEEP)
                return listOf(cleared) + executeMove(cleared.apply(state), slot, choice)
            }
            val remaining = sleepVolatile.turnsRemaining - 1
            if (remaining > 0) {
                return listOf(
                    VolatileChanged(slot, sleepVolatile, Volatile.Sleep(remaining)),
                    MoveFailed(slot, FailReason.ASLEEP)
                )
            } else {
                val cleared = StatusCleared(slot, StatusCondition.SLEEP)
                return listOf(cleared) + executeMove(cleared.apply(state), slot, choice)
            }
        }

        if (attacker.status == StatusCondition.FREEZE) {
            if (chanceCheck(20, FailReason.FROZEN)) {
                val cleared = StatusCleared(slot, StatusCondition.FREEZE)
                return listOf(cleared) + executeMove(cleared.apply(state), slot, choice)
            } else {
                return listOf(MoveFailed(slot, FailReason.FROZEN))
            }
        }

        if (attacker.status == StatusCondition.PARALYSIS) {
            if (chanceCheck(25, FailReason.FULLY_PARALYZED)) {
                return listOf(MoveFailed(slot, FailReason.FULLY_PARALYZED))
            }
        }

        return executeMove(state, slot, choice)
    }

    private fun executeMove(state: BattleState, attackerSlot: Slot, choice: TurnChoice.UseMove): List<BattleEvent> {
        val move = choice.move
        val events = mutableListOf<BattleEvent>(MoveAttempted(attackerSlot, move))
        var currentState = state

        // Resolve target slots based on move target type
        val allTargets = resolveTargetSlots(currentState, attackerSlot, move.target, choice.targetSlot)
        // For damage, exclude self (SELF-targeting moves don't deal damage to the user)
        val damageTargets = allTargets.filter { it != attackerSlot }
        val isSpread = damageTargets.size > 1
        val spreadMod = if (isSpread) 0.75 else 1.0

        // Damage phase: if the move has power, calculate damage per target
        if (move.power > 0) {
            for (targetSlot in damageTargets) {
                val attacker = currentState.pokemonFor(attackerSlot)
                val defender = currentState.pokemonFor(targetSlot)

                if (defender.isFainted) continue

                val result = calculateDamage(attacker, defender, move, roll, spreadMod)
                val damageEvent = DamageDealt(
                    target = targetSlot,
                    amount = result.damage,
                    effectiveness = result.effectiveness,
                    critical = false // TODO: critical hit logic
                )
                events.add(damageEvent)
                currentState = damageEvent.apply(currentState)

                if (currentState.pokemonFor(targetSlot).isFainted) {
                    val faintEvent = PokemonFainted(targetSlot)
                    events.add(faintEvent)
                    currentState = faintEvent.apply(currentState)
                }
            }
        }

        // Effects phase: process move effects (stat boosts, etc.)
        val faintedSlots = events.filterIsInstance<PokemonFainted>().map { it.slot }.toSet()
        for (effect in move.effects) {
            for (effectTarget in allTargets) {
                if (effectTarget in faintedSlots) continue
                events.addAll(resolveEffect(effect, attackerSlot, effectTarget))
            }
        }

        return events
    }

    /** Resolve which slots a move affects. SELF returns the attacker slot. */
    private fun resolveTargetSlots(state: BattleState, attackerSlot: Slot, target: MoveTarget, chosenTarget: Slot?): List<Slot> {
        return when (target) {
            MoveTarget.SELF -> listOf(attackerSlot)
            MoveTarget.ONE_OPPONENT -> {
                if (chosenTarget != null) {
                    require(chosenTarget.side != attackerSlot.side) {
                        "ONE_OPPONENT target must be on the opposing side"
                    }
                    listOf(chosenTarget)
                } else {
                    state.opponentSlots(attackerSlot).take(1)
                }
            }
            MoveTarget.ALL_OPPONENTS -> state.opponentSlots(attackerSlot)
            MoveTarget.ALL_OTHER -> state.allSlots().filter { it != attackerSlot }
        }
    }

    private fun resolveEffect(effect: MoveEffect, attackerSlot: Slot, targetSlot: Slot): List<BattleEvent> {
        return when (effect) {
            is MoveEffect.StatBoost -> listOf(StatChanged(targetSlot, effect.stat, effect.stages))
        }
    }
}
