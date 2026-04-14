package com.pokemon.battle.gen.simplified

import com.pokemon.battle.engine.SpeedResolver

/**
 * Simplified speed: no paralysis modifier, no item/ability/field modifiers. Ignores state.
 */
internal val SimplifiedSpeedResolver =
    SpeedResolver { pokemon, _, _ ->
        pokemon.baseEffectiveSpeed()
    }
