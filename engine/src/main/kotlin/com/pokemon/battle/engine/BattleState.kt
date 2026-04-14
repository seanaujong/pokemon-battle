package com.pokemon.battle.engine

import com.pokemon.battle.model.FieldState
import com.pokemon.battle.model.GimmickKind
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.SideCondition
import com.pokemon.battle.model.SideHazard
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.UsedGimmick

@Suppress("TooManyFunctions") // Query helpers for slots, bench, side conditions, gimmicks
data class BattleState(
    val slots: Map<Slot, PokemonState>,
    val bench: Map<Side, List<PokemonState>> = emptyMap(),
    val field: FieldState = FieldState(),
    val sideConditions: Map<Side, Map<SideCondition, Int>> = emptyMap(),
    /** Persistent switch-in traps (Stealth Rock, Spikes, etc.). Layer count per hazard. */
    val sideHazards: Map<Side, Map<SideHazard, Int>> = emptyMap(),
    /** Raw record of gimmick activations per side; legality is decided by [ruleset]. */
    val gimmicksUsedBySide: Map<Side, List<UsedGimmick>> = emptyMap(),
    /** Format-specific policy object. Defaults to [NoGimmicksRuleset] (matches pre-gimmick behavior). */
    val ruleset: Ruleset = NoGimmicksRuleset,
    val turn: Int = 1,
    /**
     * Mid-turn prompt the engine is waiting on, or null if the turn is not paused.
     * See diary 055. Phase 1: always null (no phase emits pauses yet).
     */
    val pendingInput: InputRequest? = null,
    /**
     * Events already emitted in a paused turn, so the caller can render progress
     * while waiting for input. Empty when [pendingInput] is null.
     */
    val partialTurnEvents: List<BattleEvent> = emptyList(),
) {
    /** Conditions currently active on [side], with remaining-turn counts. */
    fun sideConditionsFor(side: Side): Map<SideCondition, Int> = sideConditions[side] ?: emptyMap()

    fun withSideCondition(
        side: Side,
        condition: SideCondition,
        turnsRemaining: Int,
    ): BattleState {
        val existing = sideConditionsFor(side)
        val updated =
            if (turnsRemaining <= 0) existing - condition else existing + (condition to turnsRemaining)
        val newMap =
            if (updated.isEmpty()) sideConditions - side else sideConditions + (side to updated)
        return copy(sideConditions = newMap)
    }

    /** Hazards currently set on [side], with layer counts. Empty if none. */
    fun hazardsOn(side: Side): Map<SideHazard, Int> = sideHazards[side] ?: emptyMap()

    fun withHazardLayers(
        side: Side,
        hazard: SideHazard,
        layers: Int,
    ): BattleState {
        val existing = hazardsOn(side)
        val updated = if (layers <= 0) existing - hazard else existing + (hazard to layers)
        val newMap = if (updated.isEmpty()) sideHazards - side else sideHazards + (side to updated)
        return copy(sideHazards = newMap)
    }

    /** Gimmick activations on [side], in the order they happened. */
    fun gimmicksUsedBy(side: Side): List<UsedGimmick> = gimmicksUsedBySide[side] ?: emptyList()

    /** Delegates to [ruleset] to decide if [kind] is legal on [side] right now. */
    fun canUseGimmick(
        kind: GimmickKind,
        side: Side,
    ): Boolean = ruleset.canUseGimmick(kind, gimmicksUsedBy(side))

    /** Append a gimmick activation to the history for that side. */
    fun withGimmickUsed(used: UsedGimmick): BattleState {
        val updated = gimmicksUsedBy(used.slot.side) + used
        return copy(gimmicksUsedBySide = gimmicksUsedBySide + (used.slot.side to updated))
    }

    fun pokemonFor(slot: Slot): PokemonState = slots[slot] ?: error("No Pokemon in slot $slot")

    /**
     * Filter [candidates] down to the moves the [ruleset] would currently allow [slot]
     * to use. Convenience for AI/UI so the choice layer can skip offering an illegal
     * move; not consumed by the engine (the engine enforces via [Ruleset.canUseMove]
     * during [com.pokemon.battle.phase.MoveExecutionPhase]). See diary 039.
     */
    fun validMovesFor(
        slot: Slot,
        candidates: List<Move>,
    ): List<Move> = candidates.filter { ruleset.canUseMove(this, slot, it) is MoveLegality.Allowed }

    fun withPokemon(
        slot: Slot,
        state: PokemonState,
    ): BattleState = copy(slots = slots + (slot to state))

    fun slotsForSide(side: Side): List<Slot> = slots.keys.filter { it.side == side }.sortedBy { it.position }

    fun opponentSlots(slot: Slot): List<Slot> = slotsForSide(slot.side.opposite())

    fun allSlots(): List<Slot> = slots.keys.sortedWith(compareBy({ it.side }, { it.position }))

    fun benchFor(side: Side): List<PokemonState> = bench[side] ?: emptyList()

    fun isDefeated(side: Side): Boolean =
        slotsForSide(side).all { pokemonFor(it).isFainted } &&
            benchFor(side).isEmpty()

    companion object {
        @Suppress("LongParameterList") // Factory method — all params have defaults
        fun singles(
            p1: PokemonState,
            p2: PokemonState,
            p1Bench: List<PokemonState> = emptyList(),
            p2Bench: List<PokemonState> = emptyList(),
            field: FieldState = FieldState(),
            turn: Int = 1,
        ) = BattleState(
            slots = mapOf(Slot.p1() to p1, Slot.p2() to p2),
            bench = buildBench(p1Bench, p2Bench),
            field = field,
            turn = turn,
        )

        @Suppress("LongParameterList") // Factory method — all params are required
        fun doubles(
            p1Left: PokemonState,
            p1Right: PokemonState,
            p2Left: PokemonState,
            p2Right: PokemonState,
            p1Bench: List<PokemonState> = emptyList(),
            p2Bench: List<PokemonState> = emptyList(),
            field: FieldState = FieldState(),
            turn: Int = 1,
        ) = BattleState(
            slots =
                mapOf(
                    Slot.p1(0) to p1Left,
                    Slot.p1(1) to p1Right,
                    Slot.p2(0) to p2Left,
                    Slot.p2(1) to p2Right,
                ),
            bench = buildBench(p1Bench, p2Bench),
            field = field,
            turn = turn,
        )

        private fun buildBench(
            p1Bench: List<PokemonState>,
            p2Bench: List<PokemonState>,
        ): Map<Side, List<PokemonState>> {
            val bench = mutableMapOf<Side, List<PokemonState>>()
            if (p1Bench.isNotEmpty()) bench[Side.SIDE_1] = p1Bench
            if (p2Bench.isNotEmpty()) bench[Side.SIDE_2] = p2Bench
            return bench
        }
    }
}
