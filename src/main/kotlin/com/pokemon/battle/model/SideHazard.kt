package com.pokemon.battle.model

/**
 * Side-scoped persistent traps set by moves (Stealth Rock, Spikes, etc.). Distinct from
 * [SideCondition] (which ticks down each turn) — hazards persist until explicitly
 * removed, and fire on switch-in rather than end-of-turn.
 *
 * Layer count is stored per-side in [com.pokemon.battle.engine.BattleState.sideHazards];
 * some hazards use it (Spikes 1-3, Toxic Spikes 1-2), others ignore it (Stealth Rock,
 * Sticky Web are "set" or "not set").
 */
enum class SideHazard {
    STEALTH_ROCK,
    SPIKES,
    TOXIC_SPIKES,
    STICKY_WEB,
}
