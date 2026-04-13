package com.pokemon.battle.model

data class PokemonState(
    val pokemon: Pokemon,
    val currentHp: Int,
    val statStages: StatStages = StatStages(),
    val status: StatusCondition? = null,
    val volatiles: Set<Volatile> = emptySet(),
    val ability: Ability? = null,
    val item: Item? = null
) {
    val isFainted: Boolean get() = currentHp <= 0

    val maxHp: Int get() = pokemon.maxHp

    fun effectiveSpeed(): Double {
        val base = pokemon.calcStat(StatType.SPEED) * stageMultiplier(statStages.speed)
        return if (status == StatusCondition.PARALYSIS) base * 0.5 else base
    }
}
