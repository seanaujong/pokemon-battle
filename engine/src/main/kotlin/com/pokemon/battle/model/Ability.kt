package com.pokemon.battle.model

/**
 * Catalog of abilities the engine recognizes. Behavior lives in
 * [com.pokemon.battle.engine.ability.AbilityEffect] implementations and is looked up via
 * [com.pokemon.battle.engine.ability.AbilityRegistry]. This enum is identity only.
 */
enum class Ability {
    // Starters (pinch-type boost — currently dormant, will add AbilityEffect when wired)
    BLAZE,
    OVERGROW,
    TORRENT,

    // Weather immunity
    SAND_VEIL,
    SAND_RUSH,
    SAND_FORCE,
    SNOW_CLOAK,
    ICE_BODY,

    // Switch-in triggers
    INTIMIDATE,
    DRIZZLE,
    DROUGHT,

    // Damage immunity
    LEVITATE,

    // Item suppression
    KLUTZ,

    // Pre-damage intercept (Focus-Sash-like on ability side)
    STURDY,

    // Post-damage HP-threshold forced switch
    EMERGENCY_EXIT,

    // Switch-out triggers
    NATURAL_CURE,
}
