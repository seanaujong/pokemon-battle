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
}
