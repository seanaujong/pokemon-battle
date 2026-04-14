package com.pokemon.battle.server.team

import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Nature
import com.pokemon.battle.model.StatBlock

/**
 * Parsed Smogon-format set — species/ability/item referenced by name. The resolver
 * ([TeamBuilder]) turns this into a [com.pokemon.battle.model.PokemonState] + move pool,
 * looking up species in the [com.pokemon.battle.data.Pokedex] and moves in
 * [com.pokemon.battle.data.MoveDex].
 *
 * Legality is not enforced here: a client is free to send Charizard with Earthquake
 * and Levitate. The engine runs whatever it's given (see architecture.md). A future
 * team-validator module is where legality checks would live.
 */
data class SmogonSet(
    val species: String,
    val item: Item?,
    val ability: Ability,
    val level: Int,
    val nature: Nature,
    val ivs: StatBlock,
    val evs: StatBlock,
    val moves: List<String>,
)
