package com.pokemon.battle.engine

import com.pokemon.battle.model.*

data class BattleState(
    val slots: Map<Slot, PokemonState>,
    val bench: Map<Side, List<PokemonState>> = emptyMap(),
    val field: FieldState = FieldState(),
    val turn: Int = 1,
) {
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
