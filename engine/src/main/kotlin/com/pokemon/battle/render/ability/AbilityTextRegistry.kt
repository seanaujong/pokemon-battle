package com.pokemon.battle.render.ability

import com.pokemon.battle.model.Ability

/**
 * Rendering-only parallel of [com.pokemon.battle.engine.ability.AbilityRegistry]. Only
 * abilities with custom flavor strings register here; the fallback is the generic
 * default in [AbilityText] (`"X's AbilityName!"`) applied via an anonymous [AbilityText]
 * in [textFor].
 */
object AbilityTextRegistry {
    private val texts: Map<Ability, AbilityText> =
        listOf(
            SturdyText,
            EmergencyExitText,
        ).associateBy { it.ability }

    /**
     * Returns the registered text if present, otherwise a generic one that uses the
     * ability's enum name. Never null — every ability renders something.
     */
    fun textFor(ability: Ability): AbilityText = texts[ability] ?: GenericAbilityText(ability)

    private data class GenericAbilityText(override val ability: Ability) : AbilityText
}
