package com.pokemon.battle.data

/**
 * Static version-group → generation mapping. Stable reference data (a version group's
 * generation never changes once shipped), so it's a table rather than a `/version-group`
 * fetch. Roman-numeral generation labels match how players name eras.
 */
object GenerationMap {
    private val GENERATION_BY_VERSION_GROUP =
        mapOf(
            "red-blue" to "I", "yellow" to "I",
            "gold-silver" to "II", "crystal" to "II",
            "ruby-sapphire" to "III", "emerald" to "III", "firered-leafgreen" to "III",
            "colosseum" to "III", "xd" to "III",
            "diamond-pearl" to "IV", "platinum" to "IV", "heartgold-soulsilver" to "IV",
            "black-white" to "V", "black-2-white-2" to "V",
            "x-y" to "VI", "omega-ruby-alpha-sapphire" to "VI",
            "sun-moon" to "VII", "ultra-sun-ultra-moon" to "VII",
            "lets-go-pikachu-lets-go-eevee" to "VII",
            "sword-shield" to "VIII", "brilliant-diamond-shining-pearl" to "VIII",
            "legends-arceus" to "VIII",
            "scarlet-violet" to "IX",
        )

    private val ORDER = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX")

    fun generationOf(versionGroup: String): String? = GENERATION_BY_VERSION_GROUP[versionGroup]

    /** Sort key for a generation label; unknown labels sort last. */
    fun generationOrder(generation: String): Int = ORDER.indexOf(generation).let { if (it < 0) ORDER.size else it }
}
