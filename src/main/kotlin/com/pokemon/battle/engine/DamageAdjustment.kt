package com.pokemon.battle.engine

/**
 * Shared return type for "intercept incoming damage" hooks on both [com.pokemon.battle.engine.item.ItemEffect]
 * and [com.pokemon.battle.engine.ability.AbilityEffect]. If the hook returns a value,
 * [adjustedDamage] replaces the raw damage; [consumed] indicates whether the item should
 * be removed (abilities always pass [consumed] = false).
 */
data class DamageAdjustment(
    val adjustedDamage: Int,
    val consumed: Boolean,
)
