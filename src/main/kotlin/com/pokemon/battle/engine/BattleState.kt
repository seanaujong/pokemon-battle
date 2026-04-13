package com.pokemon.battle.engine

import com.pokemon.battle.model.*

import com.pokemon.battle.model.*

data class BattleState(
    val pokemon1: PokemonState,
    val pokemon2: PokemonState,
    val field: FieldState = FieldState(),
    val turn: Int = 1
) {
    fun pokemonFor(player: Player): PokemonState = when (player) {
        Player.P1 -> pokemon1
        Player.P2 -> pokemon2
    }

    fun withPokemon(player: Player, state: PokemonState): BattleState = when (player) {
        Player.P1 -> copy(pokemon1 = state)
        Player.P2 -> copy(pokemon2 = state)
    }
}
