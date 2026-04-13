package com.pokemon.battle.phase

import com.pokemon.battle.engine.AbilityBlocked
import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageCalculator
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.GenVDamageCalculator
import com.pokemon.battle.engine.GenVSpeedResolver
import com.pokemon.battle.engine.MoveAttempted
import com.pokemon.battle.engine.MoveFailed
import com.pokemon.battle.engine.Phase
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.ProtectBlocked
import com.pokemon.battle.engine.SpeedResolver
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.engine.StatusCleared
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.VolatileAdded
import com.pokemon.battle.engine.VolatileRemoved
import com.pokemon.battle.engine.defaultChanceCheck
import com.pokemon.battle.engine.resolveMoveOrder
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.FailReason
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveEffect
import com.pokemon.battle.model.MoveTarget
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatusCondition
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.Volatile

@Suppress("TooManyFunctions") // Move execution decomposed into focused helpers
class MoveExecutionPhase(
    private val damageCalculator: DamageCalculator = GenVDamageCalculator(),
    private val speedResolver: SpeedResolver = GenVSpeedResolver,
    private val roll: (IntRange) -> Int = { range -> range.random() },
    private val chanceCheck: ChanceCheck = defaultChanceCheck,
) : Phase {
    override fun resolve(
        state: BattleState,
        choices: TurnChoices,
    ): List<BattleEvent> {
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

    private fun checkStatusThenExecute(
        state: BattleState,
        slot: Slot,
        choice: TurnChoice.UseMove,
    ): List<BattleEvent> {
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
                    VolatileRemoved(slot, sleepVolatile),
                    VolatileAdded(slot, Volatile.Sleep(remaining)),
                    MoveFailed(slot, FailReason.ASLEEP),
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

    private fun executeMove(
        state: BattleState,
        attackerSlot: Slot,
        choice: TurnChoice.UseMove,
    ): List<BattleEvent> {
        val move = choice.move
        val events = mutableListOf<BattleEvent>(MoveAttempted(attackerSlot, move))
        var currentState = state

        // Protection moves (Protect, Detect, …) have diminishing-success semantics and
        // bypass the standard target/damage/effect flow.
        if (isProtectionMove(move)) {
            val protectionEvents = resolveProtectionMove(currentState, attackerSlot)
            events.addAll(protectionEvents)
            return events
        }

        // Any non-protection move resets the user's protection counter.
        val counterReset = clearProtectCounter(currentState, attackerSlot)
        for (event in counterReset) {
            events.add(event)
            currentState = event.apply(currentState)
        }

        val allTargets = resolveTargetSlots(currentState, attackerSlot, move.target, choice.targetSlot)

        // Per-target Protect gate: applies to BOTH damage and effects so status moves like
        // Toxic or Growl are blocked too. Self-target moves are not blocked (you can boost
        // yourself behind Protect).
        val (unblockedTargets, blockEvents) = applyProtectGate(currentState, attackerSlot, allTargets)
        events.addAll(blockEvents)

        val damageTargets = unblockedTargets.filter { it != attackerSlot }
        if (move.power > 0) {
            val damageEvents = resolveDamage(currentState, attackerSlot, move, damageTargets)
            for (event in damageEvents) {
                events.add(event)
                currentState = event.apply(currentState)
            }
        }

        val faintedSlots = events.filterIsInstance<PokemonFainted>().map { it.slot }.toSet()
        val effectEvents = resolveEffects(move.effects, unblockedTargets, faintedSlots)
        for (event in effectEvents) {
            events.add(event)
            currentState = event.apply(currentState)
        }

        return events
    }

    // --- Protect handling ---

    private fun isProtectionMove(move: Move): Boolean = move.effects.any { it is MoveEffect.SetVolatile && it.volatile == Volatile.Protect }

    private fun resolveProtectionMove(
        state: BattleState,
        attackerSlot: Slot,
    ): List<BattleEvent> {
        val attacker = state.pokemonFor(attackerSlot)
        val existingCounter = attacker.volatiles.filterIsInstance<Volatile.ProtectCounter>().firstOrNull()
        val consecutive = existingCounter?.consecutive ?: 0

        // Success chance halves each consecutive use: 100, 50, 25, 12, 6, 3, 1
        val successPercent = (100 shr consecutive).coerceAtLeast(1)
        val succeeded = chanceCheck(successPercent, FailReason.PROTECT_FAILED)

        val events = mutableListOf<BattleEvent>()
        // Counter increments regardless of success
        if (existingCounter != null) events.add(VolatileRemoved(attackerSlot, existingCounter))
        events.add(VolatileAdded(attackerSlot, Volatile.ProtectCounter(consecutive + 1)))

        if (succeeded) {
            events.add(VolatileAdded(attackerSlot, Volatile.Protect))
        } else {
            events.add(MoveFailed(attackerSlot, FailReason.PROTECT_FAILED))
        }
        return events
    }

    private fun clearProtectCounter(
        state: BattleState,
        slot: Slot,
    ): List<BattleEvent> =
        state.pokemonFor(slot).volatiles
            .filterIsInstance<Volatile.ProtectCounter>()
            .map { VolatileRemoved(slot, it) }

    private fun applyProtectGate(
        state: BattleState,
        attackerSlot: Slot,
        targets: List<Slot>,
    ): Pair<List<Slot>, List<BattleEvent>> {
        val (protected, unblocked) =
            targets.partition { target ->
                target != attackerSlot && Volatile.Protect in state.pokemonFor(target).volatiles
            }
        return unblocked to protected.map { ProtectBlocked(it) }
    }

    // --- Per-target damage resolution ---

    private fun resolveDamage(
        state: BattleState,
        attackerSlot: Slot,
        move: Move,
        targets: List<Slot>,
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

            // Crit roll: 1 in 24 chance (~4.2%) in Gen V+
            val isCritical = roll(1..24) == 1
            val result = damageCalculator.calculate(attacker, defender, move, roll, spreadMod, isCritical)
            val damageEvent =
                DamageDealt(
                    target = targetSlot,
                    amount = result.damage,
                    effectiveness = result.effectiveness,
                    critical = isCritical,
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
        effects: List<MoveEffect>,
        targets: List<Slot>,
        faintedSlots: Set<Slot>,
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

    private fun resolveEffect(
        effect: MoveEffect,
        targetSlot: Slot,
    ): List<BattleEvent> {
        return when (effect) {
            is MoveEffect.StatBoost -> listOf(StatChanged(targetSlot, effect.stat, effect.stages))
            is MoveEffect.SetVolatile -> listOf(VolatileAdded(targetSlot, effect.volatile))
        }
    }

    // --- Target resolution ---

    private fun resolveTargetSlots(
        state: BattleState,
        attackerSlot: Slot,
        target: MoveTarget,
        chosenTarget: Slot?,
    ): List<Slot> {
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

    private fun abilityBlockingMove(
        defender: PokemonState,
        move: Move,
    ): Ability? =
        when (defender.ability) {
            Ability.LEVITATE -> if (move.type == Type.GROUND && move.power > 0) Ability.LEVITATE else null
            else -> null
        }
}
