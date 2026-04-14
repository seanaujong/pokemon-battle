package com.pokemon.battle.engine.item

import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot

/**
 * Damage adjustment from a defender-side item intercept (Focus Sash-style).
 * If the item triggers, [adjustedDamage] replaces the raw damage and [consumed] indicates
 * whether the item should be removed.
 */
data class DamageAdjustment(
    val adjustedDamage: Int,
    val consumed: Boolean,
)

/**
 * A pluggable behavior for a held item. Each item has its own implementation; the
 * [ItemRegistry] looks up the effect by [Item] enum value.
 *
 * All hooks default to no-op. Each item overrides only what applies.
 *
 * This structure is designed to support gen-specific registries: Gen 3 wouldn't register
 * [Item.LIFE_ORB] (doesn't exist yet), Gen 4 would, etc. Damage calc and phases consult
 * the registry instead of switching on enum values directly — so adding an item means
 * adding a file + registry entry, not editing scattered callers.
 */
interface ItemEffect {
    val item: Item

    /** Multiplier applied to damage the holder deals. */
    fun attackerDamageModifier(
        attacker: PokemonState,
        move: Move,
    ): Double = 1.0

    /** Multiplier applied to damage the holder receives (Eviolite-style). */
    fun defenderDamageModifier(
        defender: PokemonState,
        move: Move,
    ): Double = 1.0

    /** Intercept incoming damage before it applies (Focus Sash). Returns null to pass through. */
    fun interceptIncomingDamage(
        defender: PokemonState,
        rawDamage: Int,
    ): DamageAdjustment? = null

    /** Fired once after the holder's move resolution if the move landed any damage (Life Orb). */
    fun afterUserMoveDamage(
        user: PokemonState,
        userSlot: Slot,
        damageLanded: Boolean,
    ): List<BattleEvent> = emptyList()

    /** Fired at end of each turn (Leftovers, future berries). */
    fun endOfTurn(
        pokemon: PokemonState,
        slot: Slot,
    ): List<BattleEvent> = emptyList()

    // --- Rendering ---

    fun renderHealing(
        amount: Int,
        pokemonName: String,
    ): String = ""

    fun renderConsumed(pokemonName: String): String = ""

    fun renderDamage(
        amount: Int,
        pokemonName: String,
    ): String = ""
}
