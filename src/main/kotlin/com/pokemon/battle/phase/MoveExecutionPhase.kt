package com.pokemon.battle.phase

import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*

class MoveExecutionPhase(
    private val damageCalculator: DamageCalculator = GenVDamageCalculator(),
    private val speedResolver: SpeedResolver = GenVSpeedResolver,
    private val roll: (IntRange) -> Int = { range -> range.random() },
    private val chanceCheck: ChanceCheck = defaultChanceCheck
) : Phase {

    override fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent> {
        val order = resolveMoveOrder(state, choices, speedResolver).order
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

    // --- Status checks ---

    private fun checkStatusThenExecute(state: BattleState, slot: Slot, choice: TurnChoice.UseMove): List<BattleEvent> {
        val attacker = state.pokemonFor(slot)

        if (attacker.status == StatusCondition.SLEEP) {
            val sleepVolatile = attacker.volatiles.filterIsInstance<Volatile.Sleep>().firstOrNull()
            if (sleepVolatile == null) {
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

    // --- Move execution ---

    private fun executeMove(state: BattleState, attackerSlot: Slot, choice: TurnChoice.UseMove): List<BattleEvent> {
        val move = choice.move
        val events = mutableListOf<BattleEvent>(MoveAttempted(attackerSlot, move))
        var currentState = state

        val allTargets = resolveTargetSlots(currentState, attackerSlot, move.target, choice.targetSlot)
        val damageTargets = allTargets.filter { it != attackerSlot }

        // Damage
        if (move.power > 0) {
            val damageEvents = resolveDamage(currentState, attackerSlot, move, damageTargets)
            for (event in damageEvents) {
                events.add(event)
                currentState = event.apply(currentState)
            }
        }

        // Effects
        val faintedSlots = events.filterIsInstance<PokemonFainted>().map { it.slot }.toSet()
        val effectEvents = resolveEffects(move.effects, allTargets, faintedSlots)
        for (event in effectEvents) {
            events.add(event)
            currentState = event.apply(currentState)
        }

        return events
    }

    // --- Per-target damage resolution ---

    private fun resolveDamage(
        state: BattleState, attackerSlot: Slot, move: Move, targets: List<Slot>
    ): List<BattleEvent> {
        val events = mutableListOf<BattleEvent>()
        var currentState = state
        val isSpread = targets.size > 1
        val spreadMod = if (isSpread) 0.75 else 1.0

        for (targetSlot in targets) {
            val attacker = currentState.pokemonFor(attackerSlot)
            val defender = currentState.pokemonFor(targetSlot)

            if (defender.isFainted) continue

            val blockingAbility = abilityBlockingMove(defender, move)
            if (blockingAbility != null) {
                events.add(AbilityBlocked(targetSlot, blockingAbility))
                continue
            }

            val result = damageCalculator.calculate(attacker, defender, move, roll, spreadMod)
            val damageEvent = DamageDealt(
                target = targetSlot,
                amount = result.damage,
                effectiveness = result.effectiveness,
                critical = false
            )
            events.add(damageEvent)
            currentState = damageEvent.apply(currentState)

            if (currentState.pokemonFor(targetSlot).isFainted) {
                val faintEvent = PokemonFainted(targetSlot)
                events.add(faintEvent)
                currentState = faintEvent.apply(currentState)
            }
        }

        return events
    }

    // --- Effect resolution ---

    private fun resolveEffects(
        effects: List<MoveEffect>, targets: List<Slot>, faintedSlots: Set<Slot>
    ): List<BattleEvent> {
        val events = mutableListOf<BattleEvent>()
        for (effect in effects) {
            for (target in targets) {
                if (target in faintedSlots) continue
                events.addAll(resolveEffect(effect, target))
            }
        }
        return events
    }

    private fun resolveEffect(effect: MoveEffect, targetSlot: Slot): List<BattleEvent> {
        return when (effect) {
            is MoveEffect.StatBoost -> listOf(StatChanged(targetSlot, effect.stat, effect.stages))
        }
    }

    // --- Target resolution ---

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

    // --- Ability checks ---

    private fun abilityBlockingMove(defender: PokemonState, move: Move): Ability? = when (defender.ability) {
        Ability.LEVITATE -> if (move.type == Type.GROUND && move.power > 0) Ability.LEVITATE else null
        else -> null
    }
}
