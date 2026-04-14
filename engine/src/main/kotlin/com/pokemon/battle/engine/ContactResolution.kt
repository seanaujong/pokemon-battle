package com.pokemon.battle.engine

import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.engine.item.ItemRegistry
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.PokemonState

/**
 * Does [move], executed by [attacker], count as a contact move in this context?
 *
 * Layers overrides on top of [Move.contact]:
 *
 * 1. Start with the move's static flag ([Move.contact]).
 * 2. Attacker's ability can override (Long Reach negates contact for
 *    everything; Mold Breaker doesn't touch contact but similar override
 *    shape).
 * 3. Attacker's item can override (Gen 9 Punching Glove negates contact
 *    for punching moves).
 *
 * Ability overrides take precedence over item overrides because that's the
 * game's rule-of-thumb resolution order; if two sources disagree, the
 * ability wins. Both sources' default is null ("no opinion"), so they only
 * participate when they actually have something to say.
 *
 * Diary 088 introduced this helper. The alternative — a naive
 * `move.contact` read — breaks on the Punching Glove interaction and forced
 * the seam.
 */
fun resolveIsContact(
    move: Move,
    attacker: PokemonState,
    items: ItemRegistry,
    abilities: AbilityRegistry,
): Boolean {
    val abilityOverride = abilities.effectFor(attacker.effectiveAbility)?.overridesContact(move)
    if (abilityOverride != null) return abilityOverride
    val itemOverride = items.effectForHolder(attacker)?.overridesContact(move)
    if (itemOverride != null) return itemOverride
    return move.contact
}
