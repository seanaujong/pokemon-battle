package com.pokemon.battle.model

data class Pokemon(
    val species: Species,
    val level: Int,
    val ivs: StatBlock = StatBlock.uniform(31),
    val evs: StatBlock = StatBlock.uniform(0),
    val nature: Nature = Nature.HARDY,
    /**
     * Tera type chosen at team-build time. Null means the Pokemon cannot Terastallize
     * (either the format forbids it, or the user hasn't picked one). Activation at
     * battle time is driven by [com.pokemon.battle.engine.Terastallized]; the preset
     * type lives here so it survives the whole battle. Diary 092.
     */
    val teraType: Type? = null,
) {
    init {
        require(ivs.all { it in 0..31 }) { "IVs must be 0-31" }
        require(evs.all { it in 0..252 }) { "EVs must be 0-252" }
    }

    val maxHp: Int get() = calcMaxHp(species.baseHp, level, ivs.hp, evs.hp)

    fun calcStat(stat: StatType): Int = calcStat(species.baseStat(stat), level, ivs.forStat(stat), evs.forStat(stat), nature.modifier(stat))
}
