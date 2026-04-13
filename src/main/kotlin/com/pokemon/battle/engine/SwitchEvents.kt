package com.pokemon.battle.engine

import com.pokemon.battle.model.Slot

data class SwitchOut(
    val slot: Slot,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(slot)
        val side = slot.side
        val newBench = state.benchFor(side) + pokemon
        return state.copy(bench = state.bench + (side to newBench))
    }
}

data class SwitchIn(
    val slot: Slot,
    val benchIndex: Int,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val side = slot.side
        val bench = state.benchFor(side)
        val incoming = bench[benchIndex]
        val newBench = bench.filterIndexed { i, _ -> i != benchIndex }
        return state
            .withPokemon(slot, incoming)
            .copy(bench = state.bench + (side to newBench))
    }
}
