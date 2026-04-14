package com.pokemon.battle.engine.ability

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.DamageAdjustment
import com.pokemon.battle.engine.GameEvent
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
@Suppress("TooManyFunctions") // Ability hooks grow as mechanics are added; each ability overrides only what applies.
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
    ): List<GameEvent> = emptyList()

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
        state: BattleState,
        slot: Slot,
        move: Move,
    ): List<GameEvent> = emptyList()

    /**
     * Fired when the holder voluntarily leaves the field — regular switch
     * (SwitchPhase) or self-switch move (U-turn, Volt Switch). Examples:
     * - Natural Cure: clear the holder's non-volatile status on switch-out
     * - Regenerator (future): restore 1/3 max HP on switch-out
     *
     * **Not** fired when the holder faints. Faint replacement runs through a
     * different seam (BattleLoop / faint-replacement flow after
     * [com.pokemon.battle.engine.PokemonFainted]) and is semantically distinct
     * — a fainted Pokemon doesn't "switch out", it gets replaced.
     *
     * Called before [com.pokemon.battle.engine.SwitchOut] is emitted, so the
     * outgoing Pokemon's state is still on the field (events produced here
     * apply to pre-switch state).
     */
    fun onSwitchOut(
        state: BattleState,
        slot: Slot,
    ): List<GameEvent> = emptyList()

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
     * Intercept incoming damage before it applies (Sturdy). Mirrors
     * [com.pokemon.battle.engine.item.ItemEffect.interceptIncomingDamage] on the ability
     * side. Abilities return [DamageAdjustment.consumed] = false (abilities aren't consumed).
     * Checked before items by the caller; if the ability intercepts, the item hook is
     * skipped.
     */
    fun interceptIncomingDamage(
        defender: PokemonState,
        rawDamage: Int,
    ): DamageAdjustment? = null

    /**
     * True if this ability suppresses the holder's held item (Klutz). Consulted by
     * [com.pokemon.battle.engine.item.ItemRegistry.effectForHolder] to decide if the
     * item's effect should fire.
     */
    fun suppressesHeldItem(holder: PokemonState): Boolean = false

    /** Speed multiplier on the holder (future: Swift Swim 2x in rain, Sand Rush 2x in sand). */
    fun speedModifier(holder: PokemonState): Double = 1.0

    /**
     * Overrides whether the holder's outgoing [move] counts as making contact.
     * Null = no opinion (default). True = force contact. False = negate contact.
     * Long Reach is the canonical example — every move becomes non-contact
     * regardless of its default. Mirrors [com.pokemon.battle.engine.item.ItemEffect.overridesContact];
     * both are consulted by [com.pokemon.battle.engine.resolveIsContact]. Diary 088.
     */
    fun overridesContact(move: com.pokemon.battle.model.Move): Boolean? = null

    /**
     * Fired after the holder takes damage. Mirrors
     * [com.pokemon.battle.engine.item.ItemEffect.onHpThresholdCrossed]. Used for
     * Emergency Exit (forced switch when HP drops to/below 50%) and similar.
     */
    fun onHpThresholdCrossed(
        state: BattleState,
        slot: Slot,
        previousHp: Int,
        abilities: AbilityRegistry,
    ): List<GameEvent> = emptyList()

    // Rendering lives in render/ability/AbilityText, not here. Split diary 038.
}
