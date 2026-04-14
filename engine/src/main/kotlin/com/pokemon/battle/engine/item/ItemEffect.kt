package com.pokemon.battle.engine.item

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.DamageAdjustment
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.model.Effectiveness
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
    ): List<GameEvent> = emptyList()

    /** Fired at end of each turn (Leftovers, future berries). */
    fun endOfTurn(
        state: BattleState,
        slot: Slot,
    ): List<GameEvent> = emptyList()

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
        abilities: AbilityRegistry,
    ): List<GameEvent> = emptyList()

    /**
     * Fired after the holder takes damage from an attacker. Used by Red Card (force
     * attacker switch + consume item), Rocky Helmet (damage the contact attacker),
     * Weakness Policy (boost Attack/SpAtk on super-effective hit), and future
     * "on-hit" items that react to the attacker.
     *
     * [effectiveness] carries the type-effectiveness of the incoming hit so effects
     * that only fire on a specific effectiveness bucket (Weakness Policy on
     * [Effectiveness.SUPER_EFFECTIVE]) can gate themselves without reading back
     * through prior events.
     *
     * [abilities] is threaded through so effects that force a switch (Red Card) can
     * trigger the replacement's switch-in ability without reaching for a global
     * registry.
     */
    @Suppress("LongParameterList") // Intentional: on-hit items need full attacker/defender/damage/type-eff/contact context.
    fun onHolderTookDamage(
        state: BattleState,
        holderSlot: Slot,
        attackerSlot: Slot,
        damageDealt: Int,
        effectiveness: Effectiveness,
        contact: Boolean,
        abilities: AbilityRegistry,
    ): List<GameEvent> = emptyList()

    /**
     * Whether the holder bypasses entry-hazard damage and effects on switch-in
     * (Heavy-Duty Boots). When true, [resolveHazardsOnSwitchIn] short-circuits
     * and emits no hazard events for this Pokemon.
     *
     * This is a Boolean gate rather than an event hook because Boots produce
     * no observable event — they just suppress hazards. If a future item
     * needs per-hazard control (e.g. Air Balloon, which blocks Spikes /
     * Toxic Spikes but not Stealth Rock / Sticky Web), generalize to
     * `blocksHazard(hazard: SideHazard): Boolean` at that point.
     */
    fun blocksHazards(holder: PokemonState): Boolean = false

    /**
     * Overrides whether the holder's outgoing [move] counts as making contact.
     * Returning null (the default) means "no opinion, fall through to
     * [Move.contact]." Returning true forces contact; returning false
     * negates it. Gen 9's Punching Glove negates contact for punching
     * moves; Gen 7's Protective Pads negates consequences of contact
     * (different seam). Consulted by [resolveIsContact].
     *
     * Diary 088: adding this hook (rather than gating Rocky Helmet on a
     * static `move.contact` flag) keeps defender-side effects from having
     * to know about attacker-side items.
     */
    fun overridesContact(move: com.pokemon.battle.model.Move): Boolean? = null

    // Rendering lives in render/item/ItemText, not here. Split diary 038.
}
