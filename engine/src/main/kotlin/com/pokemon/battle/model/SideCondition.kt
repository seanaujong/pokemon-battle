package com.pokemon.battle.model

/**
 * Side-scoped conditions set by moves (Tailwind, Light Screen, Reflect, Aurora Veil, etc.)
 * or by abilities. Tracked per-side with a turn counter on [com.pokemon.battle.engine.BattleState].
 *
 * Distinct from [Volatile] (per-Pokemon) and [Weather] (field-wide).
 */
enum class SideCondition {
    TAILWIND,
    // Future: LIGHT_SCREEN, REFLECT, AURORA_VEIL, TOXIC_SPIKES, STEALTH_ROCK, SPIKES
}
