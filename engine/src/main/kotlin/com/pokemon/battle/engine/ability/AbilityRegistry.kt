package com.pokemon.battle.engine.ability

import com.pokemon.battle.model.Ability

/**
 * Maps each [Ability] with meaningful behavior to its [AbilityEffect]. Callers
 * consult this registry instead of switching on [Ability] values directly.
 *
 * Abilities with no registered effect (Blaze, Overgrow, Torrent currently) are
 * dormant — the registry returns null and callers treat them as no-op.
 *
 * Gen-specific registries (GenIVAbilityRegistry, GenVIAbilityRegistry, ...) would
 * register different effect objects for abilities whose behavior changed across gens
 * (e.g. Sand Veil's evasion boost changed in Gen 6, Prankster got Dark immunity in Gen 7).
 */
object AbilityRegistry {
    private val effects: Map<Ability, AbilityEffect> =
        listOf(
            IntimidateEffect,
            DrizzleEffect,
            LevitateEffect,
            SandVeilEffect,
            SandRushEffect,
            SandForceEffect,
            SnowCloakEffect,
            IceBodyEffect,
            BlazeEffect,
            OvergrowEffect,
            TorrentEffect,
            KlutzEffect,
            SturdyEffect,
            EmergencyExitEffect,
        ).associateBy { it.ability }

    fun effectFor(ability: Ability?): AbilityEffect? = ability?.let { effects[it] }
}
