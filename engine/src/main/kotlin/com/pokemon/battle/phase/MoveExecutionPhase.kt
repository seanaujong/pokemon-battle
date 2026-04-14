package com.pokemon.battle.phase

import com.pokemon.battle.engine.AbilityBlocked
import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageCalculator
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.GenVDamageCalculator
import com.pokemon.battle.engine.HazardRemoved
import com.pokemon.battle.engine.HazardSet
import com.pokemon.battle.engine.ItemConsumed
import com.pokemon.battle.engine.MoveAttempted
import com.pokemon.battle.engine.MoveFailed
import com.pokemon.battle.engine.MoveLegality
import com.pokemon.battle.engine.Phase
import com.pokemon.battle.engine.PhaseOutput
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.ProtectBlocked
import com.pokemon.battle.engine.Registries
import com.pokemon.battle.engine.SideConditionSet
import com.pokemon.battle.engine.SpeedResolver
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.engine.StatusCleared
import com.pokemon.battle.engine.SwitchIn
import com.pokemon.battle.engine.SwitchOut
import com.pokemon.battle.engine.SwitchReason
import com.pokemon.battle.engine.SwitchTargetRequest
import com.pokemon.battle.engine.SwitchTargetResponse
import com.pokemon.battle.engine.TrickRoomSet
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnInputResolved
import com.pokemon.battle.engine.VolatileAdded
import com.pokemon.battle.engine.VolatileRemoved
import com.pokemon.battle.engine.defaultChanceCheck
import com.pokemon.battle.engine.genVSpeedResolver
import com.pokemon.battle.engine.resolveHazardsOnSwitchIn
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
    private val registries: Registries = Registries.empty,
    private val damageCalculator: DamageCalculator = GenVDamageCalculator(registries),
    private val speedResolver: SpeedResolver = genVSpeedResolver(registries),
    private val roll: (IntRange) -> Int = { range -> range.random() },
    private val chanceCheck: ChanceCheck = defaultChanceCheck,
) : Phase {
    override fun resolve(
        pipeline: com.pokemon.battle.engine.PipelineState,
        choices: TurnChoices,
    ): PhaseOutput {
        val state = pipeline.battle
        val priorEvents = pipeline.partialTurnEvents
        val order = resolveMoveOrder(state, choices, speedResolver).order
        val events = mutableListOf<GameEvent>()
        var currentState = state

        for (slot in order) {
            val step = stepForSlot(currentState, priorEvents, choices, slot) ?: continue
            for (event in step.events) {
                events.add(event)
                currentState = event.apply(currentState)
            }
            if (step.pauseRequest != null) {
                return PhaseOutput.Paused(events, step.pauseRequest)
            }
        }

        return PhaseOutput.Completed(events)
    }

    /**
     * Decide what this slot should produce this pass, or `null` to skip the slot
     * entirely. Consolidates the "not a move choice", "fainted", "already acted",
     * "mid-self-switch resume", and "normal execution" branches into one decision.
     */
    private fun stepForSlot(
        state: BattleState,
        priorEvents: List<BattleEvent>,
        choices: TurnChoices,
        slot: Slot,
    ): MoveStep? {
        val choice = choices.choiceFor(slot) as? TurnChoice.UseMove ?: return null
        if (state.pokemonFor(slot).isFainted) return null

        // Phase-progression model (diary 062): the paused slot (if any) comes from
        // the latest TurnPausedForInput event. Everything else with a MoveAttempted
        // in priorEvents already finished this turn.
        val paused = pausedSlot(priorEvents)
        if (paused == slot) {
            return MoveStep(completeSelfSwitchOnResume(state, slot, priorEvents))
        }
        if (priorEvents.any { it is MoveAttempted && it.attacker == slot }) {
            return null
        }
        return checkStatusThenExecute(state, slot, choice)
    }

    /**
     * Which slot, if any, is mid-move waiting on input. Derived from the most recent
     * [com.pokemon.battle.engine.TurnPausedForInput] in [priorEvents]. Null on a
     * fresh turn (no pauses yet) and after a fully-resolved resume cycle.
     */
    private fun pausedSlot(priorEvents: List<BattleEvent>): Slot? {
        val lastPause =
            priorEvents.filterIsInstance<com.pokemon.battle.engine.TurnPausedForInput>().lastOrNull()
                ?: return null
        return (lastPause.request as? SwitchTargetRequest)?.userSlot
    }

    /**
     * Resumes a paused self-switch move. Reads the [SwitchTargetResponse] from the
     * most recent [TurnInputResolved] in [priorEvents] and emits the switch sequence.
     */
    private fun completeSelfSwitchOnResume(
        state: BattleState,
        attackerSlot: Slot,
        priorEvents: List<BattleEvent>,
    ): List<GameEvent> {
        val response =
            priorEvents
                .filterIsInstance<TurnInputResolved>()
                .lastOrNull()
                ?.response as? SwitchTargetResponse
                ?: error("Resuming self-switch but no SwitchTargetResponse in partialTurnEvents")
        val benchIndex = response.benchIndex
        return doSelfSwitch(state, attackerSlot, benchIndex)
    }

    // --- Status checks ---

    /** Events accumulated + an optional mid-turn pause request to propagate up. */
    private data class MoveStep(
        val events: List<GameEvent>,
        val pauseRequest: SwitchTargetRequest? = null,
    )

    private fun checkStatusThenExecute(
        state: BattleState,
        slot: Slot,
        choice: TurnChoice.UseMove,
    ): MoveStep {
        val attacker = state.pokemonFor(slot)

        if (attacker.status == StatusCondition.SLEEP) {
            val sleepVolatile = attacker.volatiles.filterIsInstance<Volatile.Sleep>().firstOrNull()
            if (sleepVolatile == null) {
                val cleared = StatusCleared(slot, StatusCondition.SLEEP)
                return prepend(cleared, executeMove(cleared.apply(state), slot, choice))
            }
            val remaining = sleepVolatile.turnsRemaining - 1
            if (remaining > 0) {
                return MoveStep(
                    listOf(
                        VolatileRemoved(slot, sleepVolatile),
                        VolatileAdded(slot, Volatile.Sleep(remaining)),
                        MoveFailed(slot, FailReason.ASLEEP),
                    ),
                )
            } else {
                val cleared = StatusCleared(slot, StatusCondition.SLEEP)
                return prepend(cleared, executeMove(cleared.apply(state), slot, choice))
            }
        }

        if (attacker.status == StatusCondition.FREEZE) {
            if (chanceCheck(20, FailReason.FROZEN)) {
                val cleared = StatusCleared(slot, StatusCondition.FREEZE)
                return prepend(cleared, executeMove(cleared.apply(state), slot, choice))
            } else {
                return MoveStep(listOf(MoveFailed(slot, FailReason.FROZEN)))
            }
        }

        if (attacker.status == StatusCondition.PARALYSIS) {
            if (chanceCheck(25, FailReason.FULLY_PARALYZED)) {
                return MoveStep(listOf(MoveFailed(slot, FailReason.FULLY_PARALYZED)))
            }
        }

        return executeMove(state, slot, choice)
    }

    private fun prepend(
        event: GameEvent,
        step: MoveStep,
    ): MoveStep = MoveStep(listOf(event) + step.events, step.pauseRequest)

    // --- Move execution ---

    private fun executeMove(
        state: BattleState,
        attackerSlot: Slot,
        choice: TurnChoice.UseMove,
    ): MoveStep {
        val move = choice.move
        val events = mutableListOf<GameEvent>(MoveAttempted(attackerSlot, move))
        var currentState = state

        // Fake Out / First Impression / Mat Block fail unless the user just switched in.
        if (move.requiresJustSwitchedIn &&
            Volatile.JustSwitchedIn !in state.pokemonFor(attackerSlot).volatiles
        ) {
            events.add(MoveFailed(attackerSlot, FailReason.NOT_FIRST_TURN))
            return MoveStep(events)
        }

        // Ruleset legality check (choice-lock today; disable/taunt/encore future). The
        // engine enforces the restrictions that volatiles and format policy imply — a
        // buggy AI that submits an illegal move produces a MoveFailed rather than a
        // silent execution. See diary 039.
        val legality = state.ruleset.canUseMove(state, attackerSlot, move)
        if (legality is MoveLegality.Forbidden) {
            events.add(MoveFailed(attackerSlot, legality.reason))
            return MoveStep(events)
        }

        // Protection moves (Protect, Detect, …) have diminishing-success semantics and
        // bypass the standard target/damage/effect flow.
        if (isProtectionMove(move)) {
            val protectionEvents = resolveProtectionMove(currentState, attackerSlot)
            events.addAll(protectionEvents)
            return MoveStep(events)
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
        val effectEvents = resolveEffects(currentState, attackerSlot, move.effects, unblockedTargets, faintedSlots)
        for (event in effectEvents) {
            events.add(event)
            currentState = event.apply(currentState)
        }

        // Self-switch (U-turn, Volt Switch): after damage + effects, switch attacker out.
        if (move.effects.any { it is MoveEffect.SelfSwitch }) {
            val selfSwitchStep = resolveSelfSwitch(currentState, attackerSlot, choice, events)
            for (event in selfSwitchStep.events) {
                events.add(event)
                currentState = event.apply(currentState)
            }
            return MoveStep(events, selfSwitchStep.pauseRequest)
        }

        return MoveStep(events)
    }

    private fun resolveSelfSwitch(
        state: BattleState,
        attackerSlot: Slot,
        choice: TurnChoice.UseMove,
        priorEvents: List<BattleEvent>,
    ): MoveStep {
        // Don't switch if attacker fainted (recoil etc.) or move was fully blocked
        // (no damage landed because of Protect / type immunity / ability immunity).
        if (state.pokemonFor(attackerSlot).isFainted) return MoveStep(emptyList())
        val damageLanded = priorEvents.any { it is DamageDealt && (it).amount > 0 }
        if (!damageLanded) return MoveStep(emptyList())

        val bench = state.benchFor(attackerSlot.side)
        val eligibleIndices = bench.withIndex().filter { !it.value.isFainted }.map { it.index }
        if (eligibleIndices.isEmpty()) return MoveStep(emptyList())

        // Pre-selected target (legacy path: choice carries switchTo up front).
        val preSelected = choice.switchTo?.takeIf { it in eligibleIndices }
        if (preSelected != null) {
            return MoveStep(doSelfSwitch(state, attackerSlot, preSelected))
        }

        // Mid-turn path: ask for a target. The pipeline will halt and return NeedInput;
        // resume re-enters this phase with a TurnInputResolved in partialTurnEvents,
        // which is handled by [completeSelfSwitchOnResume] before we reach here.
        return MoveStep(
            events = emptyList(),
            pauseRequest =
                SwitchTargetRequest(
                    userSlot = attackerSlot,
                    reason = SwitchReason.SELF_SWITCH_MOVE,
                    eligibleBenchIndices = eligibleIndices,
                ),
        )
    }

    /** Pure "do the switch" event sequence. Shared by pre-selected and resume paths. */
    private fun doSelfSwitch(
        state: BattleState,
        attackerSlot: Slot,
        benchIndex: Int,
    ): List<GameEvent> {
        val events = mutableListOf<GameEvent>()
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

        for (event in resolveSwitchInAbility(currentState, attackerSlot, registries.abilities)) {
            events.add(event)
            currentState = event.apply(currentState)
        }

        for (event in resolveHazardsOnSwitchIn(currentState, attackerSlot, registries.items)) {
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
    ): List<GameEvent> {
        val attacker = state.pokemonFor(attackerSlot)
        val existingCounter = attacker.volatiles.filterIsInstance<Volatile.ProtectCounter>().firstOrNull()
        val consecutive = existingCounter?.consecutive ?: 0

        // Success chance halves each consecutive use: 100, 50, 25, 12, 6, 3, 1
        val successPercent = (100 shr consecutive).coerceAtLeast(1)
        val succeeded = chanceCheck(successPercent, FailReason.PROTECT_FAILED)

        val events = mutableListOf<GameEvent>()
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
    ): List<GameEvent> =
        state.pokemonFor(slot).volatiles
            .filterIsInstance<Volatile.ProtectCounter>()
            .map { VolatileRemoved(slot, it) }

    private fun applyProtectGate(
        state: BattleState,
        attackerSlot: Slot,
        targets: List<Slot>,
    ): Pair<List<Slot>, List<GameEvent>> {
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
    ): List<GameEvent> {
        val events = mutableListOf<GameEvent>()
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

    private fun resolveDamagePerTarget(
        state: BattleState,
        attackerSlot: Slot,
        targetSlot: Slot,
        move: Move,
        spreadMod: Double,
    ): List<GameEvent> {
        val defender = state.pokemonFor(targetSlot)
        if (defender.isFainted) return emptyList()

        val blockingAbility = abilityBlockingMove(defender, move)
        if (blockingAbility != null) return listOf(AbilityBlocked(targetSlot, blockingAbility))

        // Multi-hit moves (Rock Blast, Double Slap): sample hit count once, then loop.
        // Ability-block (above) checks once — blocked moves are blocked for all hits.
        val hits = move.hitCount?.let { roll(it) } ?: 1
        val events = mutableListOf<GameEvent>()
        var currentState = state
        repeat(hits) {
            if (currentState.pokemonFor(targetSlot).isFainted) return@repeat
            val hitEvents = applyOneHit(currentState, attackerSlot, targetSlot, move, spreadMod)
            for (event in hitEvents) {
                events.add(event)
                currentState = event.apply(currentState)
            }
        }
        return events
    }

    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
    ) // Per-hit pipeline: crit, calc, intercept, apply, post-hooks, faint. Linearly composed.
    private fun applyOneHit(
        state: BattleState,
        attackerSlot: Slot,
        targetSlot: Slot,
        move: Move,
        spreadMod: Double,
    ): List<GameEvent> {
        val attacker = state.pokemonFor(attackerSlot)
        val defender = state.pokemonFor(targetSlot)

        val events = mutableListOf<GameEvent>()
        var currentState = state

        // Crit roll: 1 in 24 chance (~4.2%) in Gen V+
        val isCritical = roll(1..24) == 1
        val result = damageCalculator.calculate(attacker, defender, move, roll, spreadMod, isCritical, currentState.field.weather)

        // Defender's ability (Sturdy) or item (Focus Sash) may intercept the damage.
        // Ability checked first — if Sturdy saves, the Sash isn't consumed.
        val abilityIntercept = registries.abilities.effectFor(defender.effectiveAbility)?.interceptIncomingDamage(defender, result.damage)
        val itemIntercept =
            if (abilityIntercept == null) {
                registries.items.effectForHolder(defender)?.interceptIncomingDamage(defender, result.damage)
            } else {
                null
            }
        val intercept = abilityIntercept ?: itemIntercept
        val finalDamage = intercept?.adjustedDamage ?: result.damage

        if (isCritical && finalDamage > 0) {
            val critEvent = com.pokemon.battle.engine.CriticalHit(targetSlot)
            events.add(critEvent)
            currentState = critEvent.apply(currentState)
        }

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
        val onHitEvents = onHitEvents(currentState, targetSlot, attackerSlot, finalDamage, result.effectiveness)
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
    ): List<GameEvent> {
        val defenderAfter = state.pokemonFor(targetSlot)
        if (defenderAfter.isFainted) return emptyList()
        val events = mutableListOf<GameEvent>()
        events.addAll(
            registries.items.effectForHolder(defenderAfter)
                ?.onHpThresholdCrossed(state, targetSlot, previousHp, registries.abilities)
                ?: emptyList(),
        )
        events.addAll(
            registries.abilities.effectFor(defenderAfter.effectiveAbility)
                ?.onHpThresholdCrossed(state, targetSlot, previousHp, registries.abilities)
                ?: emptyList(),
        )
        return events
    }

    /** On-hit hooks: items that react to the attacker (Red Card, Rocky Helmet, Weakness Policy). */
    private fun onHitEvents(
        state: BattleState,
        targetSlot: Slot,
        attackerSlot: Slot,
        damageDealt: Int,
        effectiveness: com.pokemon.battle.model.Effectiveness,
    ): List<GameEvent> {
        val defender = state.pokemonFor(targetSlot)
        if (defender.isFainted || damageDealt <= 0) return emptyList()
        return registries.items.effectForHolder(defender)
            ?.onHolderTookDamage(state, targetSlot, attackerSlot, damageDealt, effectiveness, registries.abilities)
            ?: emptyList()
    }

    /** Attacker's held item may fire a post-damage effect (e.g. Life Orb recoil, Choice lock). */
    private fun resolveAttackerItemEffects(
        state: BattleState,
        attackerSlot: Slot,
        move: Move,
        priorEvents: List<BattleEvent>,
    ): List<GameEvent> {
        val attacker = state.pokemonFor(attackerSlot)
        val effect = registries.items.effectForHolder(attacker) ?: return emptyList()
        val anyDamage = priorEvents.any { it is DamageDealt && it.amount > 0 }
        return effect.afterUserMoveDamage(state, attackerSlot, move, anyDamage)
    }

    // --- Effect resolution ---

    private fun resolveEffects(
        state: BattleState,
        attackerSlot: Slot,
        effects: List<MoveEffect>,
        targets: List<Slot>,
        faintedSlots: Set<Slot>,
    ): List<GameEvent> {
        val events = mutableListOf<GameEvent>()
        for (effect in effects) {
            // User-sided effects fire once per move (not per target). They're keyed off the
            // attacker's side, not any target.
            if (effect is MoveEffect.ClearHazardsOnUserSide || effect is MoveEffect.UserStatBoost) {
                events.addAll(resolveEffect(state, effect, attackerSlot, attackerSlot))
                continue
            }
            for (target in targets) {
                if (target in faintedSlots) continue
                events.addAll(resolveEffect(state, effect, attackerSlot, target))
            }
        }
        return events
    }

    private fun resolveEffect(
        state: BattleState,
        effect: MoveEffect,
        attackerSlot: Slot,
        targetSlot: Slot,
    ): List<GameEvent> {
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
            is MoveEffect.SetHazardOnOpposingSide -> {
                // Target resolution already selected the opposing side's slot; the hazard
                // lands on THAT slot's side (the opposing side from the user).
                val currentLayers = state.hazardsOn(targetSlot.side)[effect.hazard] ?: 0
                if (currentLayers >= effect.maxLayers) {
                    emptyList()
                } else {
                    listOf(HazardSet(targetSlot.side, effect.hazard, currentLayers + 1))
                }
            }
            is MoveEffect.UserStatBoost -> listOf(StatChanged(attackerSlot, effect.stat, effect.stages))
            is MoveEffect.ClearHazardsOnUserSide -> {
                // Clear every hazard on the attacker's own side. Emits nothing if no hazards
                // are present — the move still ran (damage / other effects already fired).
                state.hazardsOn(attackerSlot.side).keys.map { hazard ->
                    HazardRemoved(attackerSlot.side, hazard)
                }
            }
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
        val effect = registries.abilities.effectFor(ability) ?: return null
        return if (effect.blocksMove(defender, move)) ability else null
    }
}
