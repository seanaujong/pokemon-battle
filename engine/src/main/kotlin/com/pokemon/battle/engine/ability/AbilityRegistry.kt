package com.pokemon.battle.engine.ability

import com.pokemon.battle.model.Ability

/**
 * Maps each [Ability] with meaningful behavior to its [AbilityEffect]. Phases consult
 * this registry instead of switching on [Ability] values directly. Diary 071 converted
 * this from a global `object` singleton to an injectable class so items and abilities
 * could move out of `:engine`.
 *
 * Abilities with no registered effect are dormant — the registry returns null and
 * callers treat them as no-op.
 *
 * Gen-specific registries are just `AbilityRegistry(listOf(...))` with different
 * effect objects. See [com.pokemon.battle.data.GenVRegistries] for the canonical
 * Gen V bundle.
 */
class AbilityRegistry(effects: List<AbilityEffect>) {
    private val byAbility: Map<Ability, AbilityEffect> = effects.associateBy { it.ability }

    fun effectFor(ability: Ability?): AbilityEffect? = ability?.let { byAbility[it] }
}
