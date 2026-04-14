package com.pokemon.battle.engine

import com.pokemon.battle.model.FieldState
import com.pokemon.battle.model.GimmickKind
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.SideCondition
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.UsedGimmick

@Suppress("TooManyFunctions") // Query helpers for slots, bench, side conditions, gimmicks
data class BattleState(
    val slots: Map<Slot, PokemonState>,
    val bench: Map<Side, List<PokemonState>> = emptyMap(),
    val field: FieldState = FieldState(),
    val sideConditions: Map<Side, Map<SideCondition, Int>> = emptyMap(),
    /** Raw record of gimmick activations per side; legality is decided by [ruleset]. */
    val gimmicksUsedBySide: Map<Side, List<UsedGimmick>> = emptyMap(),
    /** Format-specific policy object. Defaults to [NoGimmicksRuleset] (matches pre-gimmick behavior). */
    val ruleset: Ruleset = NoGimmicksRuleset,
    val turn: Int = 1,
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
