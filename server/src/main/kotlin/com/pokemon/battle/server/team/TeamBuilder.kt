package com.pokemon.battle.server.team

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Species

/**
 * Resolved member of a Smogon-parsed team: the [PokemonState] the engine sees plus the
 * [moves] the choice layer draws from. Move pools live with the choice provider, not on
 * [Pokemon] — see the "resist the god object" principle in architecture.md.
 */
data class ResolvedPokemon(
    val state: PokemonState,
    val moves: List<Move>,
)

object TeamBuilder {
    fun build(
        sets: List<SmogonSet>,
        pokedex: Map<String, Species>,
    ): List<ResolvedPokemon> = sets.map { build(it, pokedex) }

    private fun build(
        set: SmogonSet,
        pokedex: Map<String, Species>,
    ): ResolvedPokemon {
        val species = pokedex[set.species] ?: error("Unknown species: ${set.species}")
        val pokemon =
            Pokemon(
                species = species,
                level = set.level,
                ivs = set.ivs,
                evs = set.evs,
                nature = set.nature,
            )
        val state =
            PokemonState(
                pokemon = pokemon,
                currentHp = pokemon.maxHp,
                ability = set.ability,
                item = set.item,
            )
        val moves = set.moves.map { MoveDex[it] }
        return ResolvedPokemon(state, moves)
    }
}
