package com.pokemon.battle.engine.item

import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.PokemonState

/**
 * Eviolite: boosts the holder's Defense and Special Defense by 1.5× if the species can
 * still evolve.
 *
 * The "can still evolve" check is a team-construction concern in our architecture: we
 * trust that if a Pokemon holds Eviolite, it's an eligible species. No species-level
 * evolution data lives in the engine; validation belongs in the AI / team-builder layer.
 * (Smogon's engine makes the same choice.)
 */
object EvioliteEffect : ItemEffect {
    override val item = Item.EVIOLITE

    override fun defenderDamageModifier(
        defender: PokemonState,
        move: Move,
    ): Double =
        when (move.category) {
            // Eviolite boosts Def/SpDef by 1.5x. Damage is inversely proportional to
            // defense stat, so the equivalent damage modifier is 1/1.5 (~0.6667).
            MoveCategory.PHYSICAL, MoveCategory.SPECIAL -> EVIOLITE_DAMAGE_MULTIPLIER
            MoveCategory.STATUS -> 1.0
        }

    private const val EVIOLITE_DAMAGE_MULTIPLIER = 1.0 / 1.5
}
