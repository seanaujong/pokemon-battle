package com.pokemon.battle.engine.ability

import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Weather

/**
 * A pluggable behavior for a Pokemon ability. Each ability has its own implementation;
 * the [AbilityRegistry] looks up the effect by [Ability] enum value.
 *
 * All hooks default to no-op. Each ability overrides only what applies.
 *
 * Gen-specific registries (GenIV, GenV, ...) will register different abilities per gen
 * — abilities that don't exist yet in older gens simply aren't registered there.
 * Abilities that changed behavior across gens get different effect implementations
 * registered per gen (e.g. Sand Veil's evasion boost changed in Gen 6).
 */
interface AbilityEffect {
    val ability: Ability

    /**
     * Fired when the holder switches into an active slot. Examples:
     * - Intimidate: drop opponent Attack by 1 stage
     * - Drizzle: set rain weather for 5 turns
     */
    fun onSwitchIn(
        state: BattleState,
        slot: Slot,
    ): List<BattleEvent> = emptyList()

    /**
     * True if this ability blocks the incoming move based on its type. Examples:
     * - Levitate blocks Ground moves
     * - Flash Fire blocks Fire moves (future)
     */
    fun blocksMove(
        defender: PokemonState,
        move: Move,
    ): Boolean = false

    /**
     * Events to emit when the ability absorbs an attack (not just blocks it). Examples:
     * - Sap Sipper: absorb Grass and gain +1 Attack (future)
     * - Lightning Rod: absorb Electric and gain +1 SpAtk (future)
     *
     * Called only when [blocksMove] returned true. Returns empty by default (simple block).
     */
    fun onMoveAbsorbed(
        defender: PokemonState,
        slot: Slot,
        move: Move,
    ): List<BattleEvent> = emptyList()

    /** True if this ability grants immunity to weather chip damage. */
    fun blocksWeatherDamage(weather: Weather): Boolean = false

    /** Attacker-side damage multiplier (future: Sheer Force, Tough Claws, pinch-type boosts). */
    fun attackerDamageModifier(
        attacker: PokemonState,
        move: Move,
    ): Double = 1.0

    /** Defender-side damage multiplier (future: Thick Fat, Heatproof, Filter). */
    fun defenderDamageModifier(
        defender: PokemonState,
        move: Move,
    ): Double = 1.0

    /**
     * Game-text for when this ability triggers (switch-in announcement, absorb message).
     * Default renders as "X's <AbilityName>!"; abilities with richer flavor text override.
     */
    fun renderTriggered(pokemonName: String): String = "$pokemonName's ${abilityDisplayName()}!"

    /** Game-text for when this ability blocks an attack. */
    fun renderBlocked(pokemonName: String): String = "It doesn't affect $pokemonName... (${abilityDisplayName()})"

    private fun abilityDisplayName(): String = ability.name.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }
}
