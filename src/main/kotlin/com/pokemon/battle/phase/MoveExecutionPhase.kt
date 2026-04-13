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

            val newEvents = checkStatusThenExecute(currentState, slot, choice.move)
            for (event in newEvents) {
                events.add(event)
                currentState = event.apply(currentState)
            }
        }

        return events
    }

    private fun checkStatusThenExecute(state: BattleState, slot: Slot, move: Move): List<BattleEvent> {
        val attacker = state.pokemonFor(slot)

        // Sleep check: decrement counter, fail if still asleep
        if (attacker.status == StatusCondition.SLEEP) {
            val sleepVolatile = attacker.volatiles.filterIsInstance<Volatile.Sleep>().firstOrNull()
            if (sleepVolatile != null) {
                val remaining = sleepVolatile.turnsRemaining - 1
                if (remaining > 0) {
                    return listOf(
                        VolatileChanged(slot, sleepVolatile, Volatile.Sleep(remaining)),
                        MoveFailed(slot, FailReason.ASLEEP)
                    )
                } else {
                    val cleared = StatusCleared(slot, StatusCondition.SLEEP)
                    return listOf(cleared) + executeMove(cleared.apply(state), slot, move)
                }
            }
        }

        // Freeze check: per-turn 20% thaw chance
        if (attacker.status == StatusCondition.FREEZE) {
            if (chanceCheck(20, FailReason.FROZEN)) {
                val cleared = StatusCleared(slot, StatusCondition.FREEZE)
                return listOf(cleared) + executeMove(cleared.apply(state), slot, move)
            } else {
                return listOf(MoveFailed(slot, FailReason.FROZEN))
            }
        }

        // Paralysis check: 25% chance to skip
        if (attacker.status == StatusCondition.PARALYSIS) {
            if (chanceCheck(25, FailReason.FULLY_PARALYZED)) {
                return listOf(MoveFailed(slot, FailReason.FULLY_PARALYZED))
            }
        }

        return executeMove(state, slot, move)
    }

    private fun executeMove(state: BattleState, attackerSlot: Slot, move: Move): List<BattleEvent> {
        val events = mutableListOf<BattleEvent>(MoveAttempted(attackerSlot, move))

        // Resolve target slots based on move target type
        val targetSlots = resolveTargets(state, attackerSlot, move.target)

        // Damage phase: if the move has power, calculate damage per target
        if (move.power > 0 && targetSlots.isNotEmpty()) {
            for (targetSlot in targetSlots) {
                val attacker = state.pokemonFor(attackerSlot)
                val defender = state.pokemonFor(targetSlot)

                if (defender.isFainted) continue

                val result = calculateDamage(attacker, defender, move, roll)
                val damageEvent = DamageDealt(
                    target = targetSlot,
                    amount = result.damage,
                    effectiveness = result.effectiveness,
                    critical = false // TODO: critical hit logic
                )
                events.add(damageEvent)

                val stateAfterDamage = damageEvent.apply(state)
                if (stateAfterDamage.pokemonFor(targetSlot).isFainted) {
                    events.add(PokemonFainted(targetSlot))
                }
            }
        }

        // Effects phase: process move effects (stat boosts, etc.)
        val faintedSlots = events.filterIsInstance<PokemonFainted>().map { it.slot }.toSet()
        for (effect in move.effects) {
            val effectTargets = resolveEffectTargets(state, attackerSlot, move.target)
            for (effectTarget in effectTargets) {
                if (effectTarget in faintedSlots) continue
                events.addAll(resolveEffect(effect, attackerSlot, effectTarget))
            }
        }

        return events
    }

    /** Resolve which slots a move targets for damage. */
    private fun resolveTargets(state: BattleState, attackerSlot: Slot, target: MoveTarget): List<Slot> {
        return when (target) {
            MoveTarget.OPPONENT -> state.opponentSlots(attackerSlot).take(1) // singles: one opponent
            MoveTarget.SELF -> emptyList() // self-targeting moves don't deal damage to others
        }
    }

    /** Resolve which slots a move's effects apply to. */
    private fun resolveEffectTargets(state: BattleState, attackerSlot: Slot, target: MoveTarget): List<Slot> {
        return when (target) {
            MoveTarget.SELF -> listOf(attackerSlot)
            MoveTarget.OPPONENT -> state.opponentSlots(attackerSlot).take(1)
        }
    }

    private fun resolveEffect(effect: MoveEffect, attackerSlot: Slot, targetSlot: Slot): List<BattleEvent> {
        return when (effect) {
            is MoveEffect.StatBoost -> listOf(StatChanged(targetSlot, effect.stat, effect.stages))
        }
    }
}
