package com.pokemon.battle.engine.item

import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.DamageAdjustment
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot

/**
 * A pluggable behavior for a held item. Each item has its own implementation; the
 * [ItemRegistry] looks up the effect by [Item] enum value.
 *
 * All hooks default to no-op. Each item overrides only what applies.
 *
 * **Hook convention**: stateful hooks take [state] + [slot] (and any extra context
 * specific to the trigger). The holder's [PokemonState] is derived via
 * `state.pokemonFor(slot)`. This keeps signatures short and prevents state from going
 * stale as earlier hooks in the chain emit events.
 *
 * This structure is designed to support gen-specific registries: Gen 3 wouldn't register
 * [Item.LIFE_ORB] (doesn't exist yet), Gen 4 would, etc. Damage calc and phases consult
 * the registry instead of switching on enum values directly — so adding an item means
 * adding a file + registry entry, not editing scattered callers.
 */
@Suppress("TooManyFunctions") // Each hook covers a distinct item capability
interface ItemEffect {
    val item: Item

    /** Multiplier applied to damage the holder deals. Called from the damage calc, which
     * is state-light — only [attacker] and [move] are in scope. */
    fun attackerDamageModifier(
        attacker: PokemonState,
        move: Move,
    ): Double = 1.0

    /** Multiplier applied to damage the holder receives (Eviolite-style). Same scope as
     * [attackerDamageModifier]. */
    fun defenderDamageModifier(
        defender: PokemonState,
        move: Move,
    ): Double = 1.0

    /** Intercept incoming damage before it applies (Focus Sash). Called from the damage
     * calc; only [defender] and [rawDamage] are in scope. Returns null to pass through. */
    fun interceptIncomingDamage(
        defender: PokemonState,
        rawDamage: Int,
    ): DamageAdjustment? = null

    /**
     * Fired once after the holder's move resolution. [damageLanded] is false if the move
     * was fully blocked / missed / immune. [move] is the move that was used (Choice items
     * use it to emit a move-specific lock volatile; Life Orb ignores it).
     */
    fun afterUserMoveDamage(
        state: BattleState,
        userSlot: Slot,
        move: Move,
        damageLanded: Boolean,
    ): List<BattleEvent> = emptyList()

    /** Fired at end of each turn (Leftovers, future berries). */
    fun endOfTurn(
        state: BattleState,
        slot: Slot,
    ): List<BattleEvent> = emptyList()

    /** Speed multiplier on the holder (Choice Scarf: 1.5x; Iron Ball: 0.5x; etc.). */
    fun speedModifier(holder: PokemonState): Double = 1.0

    /**
     * Fired after the holder takes damage. Use [previousHp] vs the holder's current HP
     * (via `state.pokemonFor(slot).currentHp`) to check for a threshold crossing
     * (Sitrus at 50%, pinch berries at 25%). [state] is passed for effects that need to
     * emit switch events or consult the bench.
     */
    fun onHpThresholdCrossed(
        state: BattleState,
        slot: Slot,
        previousHp: Int,
    ): List<BattleEvent> = emptyList()

    /**
     * Fired after the holder takes damage from an attacker. Used by Red Card (force
     * attacker switch + consume item), Rocky Helmet (damage the contact attacker),
     * and future "on-hit" items that react to the attacker.
     */
    fun onHolderTookDamage(
        state: BattleState,
        holderSlot: Slot,
        attackerSlot: Slot,
        damageDealt: Int,
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
