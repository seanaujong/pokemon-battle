package com.pokemon.battle.render.ability

import com.pokemon.battle.model.Ability

/**
 * Per-ability render strings. The default behavior uses the enum name formatted as
 * `"X's AbilityName!"` / `"It doesn't affect X... (AbilityName)"` — most abilities only
 * need the default. Abilities with custom flavor (Sturdy, Emergency Exit) override.
 *
 * Separated from [com.pokemon.battle.engine.ability.AbilityEffect] (behavior) so renderers
 * can swap text sets independently — localization, Showdown-style compact text, etc.
 */
interface AbilityText {
    val ability: Ability

    fun renderTriggered(pokemonName: String): String = "$pokemonName's ${displayName()}!"

    fun renderBlocked(pokemonName: String): String = "It doesn't affect $pokemonName... (${displayName()})"

    private fun displayName(): String = ability.name.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }
}
