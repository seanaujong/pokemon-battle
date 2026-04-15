package com.pokemon.battle.model

data class PokemonState(
    val pokemon: Pokemon,
    val currentHp: Int,
    val statStages: StatStages = StatStages(),
    val status: StatusCondition? = null,
    val volatiles: Set<Volatile> = emptySet(),
    val ability: Ability? = null,
    val item: Item? = null,
    val typeOverride: List<Type>? = null,
    /**
     * Set when the holder's ability is replaced mid-battle (Mega Evolution, Gastro Acid,
     * Role Play, Trace, Simple Beam, Neutralizing Gas). Null means "use the base
     * [ability]". Read via [effectiveAbility]; never dereferenced directly.
     */
    val abilityOverride: Ability? = null,
    /**
     * True once this Pokemon has Terastallized this battle. Drives the Tera STAB rule
     * in [com.pokemon.battle.engine.GenVDamageCalculator] and pairs with [typeOverride]
     * (set to the tera type on activation). Diary 092.
     */
    val terastallized: Boolean = false,
) {
    val isFainted: Boolean get() = currentHp <= 0

    val maxHp: Int get() = pokemon.maxHp

    /** Effective types — overridden by Terastallization, Camomons, etc. */
    val effectiveTypes: List<Type> get() = typeOverride ?: pokemon.species.types

    /**
     * The ability the engine should consult right now. For Mega Evolution and similar,
     * this differs from [ability] (the base/team-declared ability). All
     * [com.pokemon.battle.engine.ability.AbilityRegistry] lookups should use this.
     */
    val effectiveAbility: Ability? get() = abilityOverride ?: ability

    /** Base speed with stat stages applied. No status/ability/item modifiers — those are gen-specific. */
    fun baseEffectiveSpeed(): Double = pokemon.calcStat(StatType.SPEED) * stageMultiplier(statStages.speed)

    /**
     * True if the Pokemon is subject to ground-based effects (Spikes, Toxic Spikes,
     * Sticky Web, Ground-type moves). Minimal check: not a Flying type and not Levitate.
     * Future: Air Balloon item, Magnet Rise volatile, Ingrain, Gravity field, Iron Ball.
     */
    val isGrounded: Boolean get() = Type.FLYING !in effectiveTypes && (abilityOverride ?: ability) != Ability.LEVITATE
}
