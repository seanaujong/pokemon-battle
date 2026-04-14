package com.pokemon.battle.data

import com.pokemon.battle.model.Species
import com.pokemon.battle.model.Type
import kotlinx.serialization.Serializable

/**
 * On-disk JSON shape for a species. Decoupled from the domain [Species] so the domain
 * model can evolve without breaking stored files. See diary 041.
 */
@Serializable
data class SpeciesJson(
    val name: String,
    val types: List<String>,
    val baseHp: Int,
    val baseAttack: Int,
    val baseDefense: Int,
    val baseSpecialAttack: Int,
    val baseSpecialDefense: Int,
    val baseSpeed: Int,
) {
    fun toDomain(): Species =
        Species(
            name = name,
            types = types.map(Type::valueOf),
            baseHp = baseHp,
            baseAttack = baseAttack,
            baseDefense = baseDefense,
            baseSpecialAttack = baseSpecialAttack,
            baseSpecialDefense = baseSpecialDefense,
            baseSpeed = baseSpeed,
        )
}
