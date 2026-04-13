package com.pokemon.battle.engine

import com.pokemon.battle.model.*

data class BattleState(
    val slots: Map<Slot, PokemonState>,
    val field: FieldState = FieldState(),
    val turn: Int = 1
) {
    fun pokemonFor(slot: Slot): PokemonState =
        slots[slot] ?: error("No Pokemon in slot $slot")

    fun withPokemon(slot: Slot, state: PokemonState): BattleState =
        copy(slots = slots + (slot to state))

    fun slotsForSide(side: Side): List<Slot> =
        slots.keys.filter { it.side == side }.sortedBy { it.position }

    fun opponentSlots(slot: Slot): List<Slot> =
        slotsForSide(slot.side.opposite())

    fun allSlots(): List<Slot> =
        slots.keys.sortedWith(compareBy({ it.side }, { it.position }))

    companion object {
        fun singles(p1: PokemonState, p2: PokemonState, field: FieldState = FieldState(), turn: Int = 1) =
            BattleState(
                slots = mapOf(Slot.p1() to p1, Slot.p2() to p2),
                field = field,
                turn = turn
            )
    }
}
