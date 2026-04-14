package com.pokemon.battle.phase

import com.pokemon.battle.engine.AbilityBlocked
import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageCalculator
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.GenVDamageCalculator
import com.pokemon.battle.engine.GenVSpeedResolver
import com.pokemon.battle.engine.ItemConsumed
import com.pokemon.battle.engine.MoveAttempted
import com.pokemon.battle.engine.MoveFailed
import com.pokemon.battle.engine.Phase
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.ProtectBlocked
import com.pokemon.battle.engine.SideConditionSet
import com.pokemon.battle.engine.SpeedResolver
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.engine.StatusCleared
import com.pokemon.battle.engine.SwitchIn
import com.pokemon.battle.engine.SwitchOut
import com.pokemon.battle.engine.TrickRoomSet
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.VolatileAdded
import com.pokemon.battle.engine.VolatileRemoved
import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.engine.defaultChanceCheck
import com.pokemon.battle.engine.item.ItemRegistry
import com.pokemon.battle.engine.resolveMoveOrder
import com.pokemon.battle.engine.resolveSwitchInAbility
import com.pokemon.battle.engine.resolveSwitchOutClearing
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.FailReason
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveEffect
import com.pokemon.battle.model.MoveTarget
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatusCondition
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

        // Fake Out / First Impression / Mat Block fail unless the user just switched in.
        if (move.requiresJustSwitchedIn &&
            Volatile.JustSwitchedIn !in state.pokemonFor(attackerSlot).volatiles
        ) {
            events.add(MoveFailed(attackerSlot, FailReason.NOT_FIRST_TURN))
            return events
        }

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
        val effectEvents = resolveEffects(currentState, move.effects, unblockedTargets, faintedSlots)
        for (event in effectEvents) {
            events.add(event)
            currentState = event.apply(currentState)
        }

        // Self-switch (U-turn, Volt Switch): after damage + effects, switch attacker out.
        if (move.effects.any { it is MoveEffect.SelfSwitch }) {
            val selfSwitchEvents = resolveSelfSwitch(currentState, attackerSlot, choice, events)
            for (event in selfSwitchEvents) {
                events.add(event)
                currentState = event.apply(currentState)
            }
        }

        return events
    }

    private fun resolveSelfSwitch(
        state: BattleState,
        attackerSlot: Slot,
        choice: TurnChoice.UseMove,
        priorEvents: List<BattleEvent>,
    ): List<BattleEvent> {
        // Don't switch if attacker fainted (recoil etc.) or move was fully blocked
        // (no damage landed because of Protect / type immunity / ability immunity).
        if (state.pokemonFor(attackerSlot).isFainted) return emptyList()
        val damageLanded = priorEvents.any { it is DamageDealt && (it).amount > 0 }
        if (!damageLanded) return emptyList()

        // Need a valid bench replacement
        val benchIndex = choice.switchTo ?: return emptyList()
        val bench = state.benchFor(attackerSlot.side)
        if (benchIndex !in bench.indices) return emptyList()
        if (bench[benchIndex].isFainted) return emptyList()

        val events = mutableListOf<BattleEvent>()
        var currentState = state

        for (event in resolveSwitchOutClearing(currentState, attackerSlot)) {
            events.add(event)
            currentState = event.apply(currentState)
        }

        val switchOut = SwitchOut(attackerSlot)
        events.add(switchOut)
        currentState = switchOut.apply(currentState)

        val switchIn = SwitchIn(attackerSlot, benchIndex)
        events.add(switchIn)
        currentState = switchIn.apply(currentState)

        val justSwitchedIn = VolatileAdded(attackerSlot, Volatile.JustSwitchedIn)
        events.add(justSwitchedIn)
        currentState = justSwitchedIn.apply(currentState)

        for (event in resolveSwitchInAbility(currentState, attackerSlot)) {
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
            val perTargetEvents = resolveDamagePerTarget(currentState, attackerSlot, targetSlot, move, spreadMod)
            for (event in perTargetEvents) {
                events.add(event)
                currentState = event.apply(currentState)
            }
        }

        events.addAll(resolveAttackerItemEffects(currentState, attackerSlot, move, events))
        return events
    }

    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
    ) // Per-target damage pipeline: intercept, apply, post-hooks, faint. Linearly composed.
    private fun resolveDamagePerTarget(
        state: BattleState,
        attackerSlot: Slot,
        targetSlot: Slot,
        move: Move,
        spreadMod: Double,
    ): List<BattleEvent> {
        val attacker = state.pokemonFor(attackerSlot)
        val defender = state.pokemonFor(targetSlot)

        if (defender.isFainted) return emptyList()

        val blockingAbility = abilityBlockingMove(defender, move)
        if (blockingAbility != null) return listOf(AbilityBlocked(targetSlot, blockingAbility))

        val events = mutableListOf<BattleEvent>()
        var currentState = state

        // Crit roll: 1 in 24 chance (~4.2%) in Gen V+
        val isCritical = roll(1..24) == 1
        val result = damageCalculator.calculate(attacker, defender, move, roll, spreadMod, isCritical, currentState.field.weather)

        // Defender's ability (Sturdy) or item (Focus Sash) may intercept the damage.
        // Ability checked first — if Sturdy saves, the Sash isn't consumed.
        val abilityIntercept = AbilityRegistry.effectFor(defender.effectiveAbility)?.interceptIncomingDamage(defender, result.damage)
        val itemIntercept =
            if (abilityIntercept == null) {
                ItemRegistry.effectForHolder(defender)?.interceptIncomingDamage(defender, result.damage)
            } else {
                null
            }
        val intercept = abilityIntercept ?: itemIntercept
        val finalDamage = intercept?.adjustedDamage ?: result.damage

        val damageEvent = DamageDealt(targetSlot, finalDamage, result.effectiveness, isCritical)
        events.add(damageEvent)
        currentState = damageEvent.apply(currentState)

        if (abilityIntercept != null && defender.effectiveAbility != null) {
            val triggered = AbilityTriggered(targetSlot, defender.effectiveAbility!!)
            events.add(triggered)
            currentState = triggered.apply(currentState)
        }
        if (intercept?.consumed == true && defender.item != null) {
            val consumed = ItemConsumed(targetSlot, defender.item)
            events.add(consumed)
            currentState = consumed.apply(currentState)
        }

        // Post-damage defender hooks
        val thresholdEvents = thresholdEvents(currentState, targetSlot, defender.currentHp)
        for (event in thresholdEvents) {
            events.add(event)
            currentState = event.apply(currentState)
        }
        val onHitEvents = onHitEvents(currentState, targetSlot, attackerSlot, finalDamage)
        for (event in onHitEvents) {
            events.add(event)
            currentState = event.apply(currentState)
        }

        if (currentState.pokemonFor(targetSlot).isFainted) {
            events.add(PokemonFainted(targetSlot))
        }
        return events
    }

    /** Post-damage HP-threshold hooks: items (Sitrus, pinch berries) + abilities (Emergency Exit). */
    private fun thresholdEvents(
        state: BattleState,
        targetSlot: Slot,
        previousHp: Int,
    ): List<BattleEvent> {
        val defenderAfter = state.pokemonFor(targetSlot)
        if (defenderAfter.isFainted) return emptyList()
        val events = mutableListOf<BattleEvent>()
        events.addAll(
            ItemRegistry.effectForHolder(defenderAfter)
                ?.onHpThresholdCrossed(state, targetSlot, previousHp)
                ?: emptyList(),
        )
        events.addAll(
            AbilityRegistry.effectFor(defenderAfter.effectiveAbility)
                ?.onHpThresholdCrossed(state, targetSlot, previousHp)
                ?: emptyList(),
        )
        return events
    }

    /** On-hit hooks: items that react to the attacker (Red Card, future Rocky Helmet). */
    private fun onHitEvents(
        state: BattleState,
        targetSlot: Slot,
        attackerSlot: Slot,
        damageDealt: Int,
    ): List<BattleEvent> {
        val defender = state.pokemonFor(targetSlot)
        if (defender.isFainted || damageDealt <= 0) return emptyList()
        return ItemRegistry.effectForHolder(defender)
            ?.onHolderTookDamage(state, targetSlot, attackerSlot, damageDealt)
            ?: emptyList()
    }

    /** Attacker's held item may fire a post-damage effect (e.g. Life Orb recoil, Choice lock). */
    private fun resolveAttackerItemEffects(
        state: BattleState,
        attackerSlot: Slot,
        move: Move,
        priorEvents: List<BattleEvent>,
    ): List<BattleEvent> {
        val attacker = state.pokemonFor(attackerSlot)
        val effect = ItemRegistry.effectForHolder(attacker) ?: return emptyList()
        val anyDamage = priorEvents.any { it is DamageDealt && it.amount > 0 }
        return effect.afterUserMoveDamage(state, attackerSlot, move, anyDamage)
    }

    // --- Effect resolution ---

    private fun resolveEffects(
        state: BattleState,
        effects: List<MoveEffect>,
        targets: List<Slot>,
        faintedSlots: Set<Slot>,
    ): List<BattleEvent> {
        val events = mutableListOf<BattleEvent>()
        for (effect in effects) {
            for (target in targets) {
                if (target in faintedSlots) continue
                events.addAll(resolveEffect(state, effect, target))
            }
        }
        return events
    }

    private fun resolveEffect(
        state: BattleState,
        effect: MoveEffect,
        targetSlot: Slot,
    ): List<BattleEvent> {
        return when (effect) {
            is MoveEffect.StatBoost -> listOf(StatChanged(targetSlot, effect.stat, effect.stages))
            is MoveEffect.SetVolatile -> listOf(VolatileAdded(targetSlot, effect.volatile))
            // SelfSwitch is handled in executeMove after damage + effects (needs state, bench, choice).
            is MoveEffect.SelfSwitch -> emptyList()
            is MoveEffect.SetTrickRoom -> {
                // Toggle: if already active, clear; else set for the specified turns.
                val currentlyActive = state.field.trickRoomTurnsRemaining > 0
                listOf(TrickRoomSet(if (currentlyActive) 0 else effect.turns))
            }
            is MoveEffect.SetSideConditionOnUserSide ->
                listOf(SideConditionSet(targetSlot.side, effect.condition, effect.turns))
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
    ): Ability? {
        val ability = defender.effectiveAbility ?: return null
        val effect = AbilityRegistry.effectFor(ability) ?: return null
        return if (effect.blocksMove(defender, move)) ability else null
    }
}
