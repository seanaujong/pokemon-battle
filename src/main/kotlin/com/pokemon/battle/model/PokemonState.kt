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
) {
    val isFainted: Boolean get() = currentHp <= 0

    val maxHp: Int get() = pokemon.maxHp

    /** Effective types — overridden by Terastallization, Camomons, etc. */
    val effectiveTypes: List<Type> get() = typeOverride ?: pokemon.species.types

    /** Base speed with stat stages applied. No status/ability/item modifiers — those are gen-specific. */
    fun baseEffectiveSpeed(): Double = pokemon.calcStat(StatType.SPEED) * stageMultiplier(statStages.speed)
}
