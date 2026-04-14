package com.pokemon.battle.render.item

import com.pokemon.battle.model.Item

/**
 * Per-item render strings. Separated from behavior ([com.pokemon.battle.engine.item.ItemEffect])
 * so renderers can swap text sets independently of behavior — localization, alternate
 * themes (Pokemon Showdown-style compact text, game-accurate flavor text), or
 * structured output (JSON events) without touching the engine.
 *
 * Returns null for events the item doesn't emit (Leftovers never consumes, Sash never
 * heals, etc.). TextRenderer treats null as "no output for this event."
 */
interface ItemText {
    val item: Item

    fun renderHealing(
        amount: Int,
        pokemonName: String,
    ): String? = null

    fun renderConsumed(pokemonName: String): String? = null

    fun renderDamage(
        amount: Int,
        pokemonName: String,
    ): String? = null
}
