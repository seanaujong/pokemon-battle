package com.pokemon.battle.data

import com.pokemon.battle.data.ability.BlazeEffect
import com.pokemon.battle.data.ability.DrizzleEffect
import com.pokemon.battle.data.ability.DroughtEffect
import com.pokemon.battle.data.ability.EmergencyExitEffect
import com.pokemon.battle.data.ability.IceBodyEffect
import com.pokemon.battle.data.ability.IntimidateEffect
import com.pokemon.battle.data.ability.KlutzEffect
import com.pokemon.battle.data.ability.LevitateEffect
import com.pokemon.battle.data.ability.NaturalCureEffect
import com.pokemon.battle.data.ability.OvergrowEffect
import com.pokemon.battle.data.ability.SandForceEffect
import com.pokemon.battle.data.ability.SandRushEffect
import com.pokemon.battle.data.ability.SandVeilEffect
import com.pokemon.battle.data.ability.SnowCloakEffect
import com.pokemon.battle.data.ability.SturdyEffect
import com.pokemon.battle.data.ability.TorrentEffect
import com.pokemon.battle.data.item.ChoiceBandEffect
import com.pokemon.battle.data.item.ChoiceScarfEffect
import com.pokemon.battle.data.item.ChoiceSpecsEffect
import com.pokemon.battle.data.item.EvioliteEffect
import com.pokemon.battle.data.item.FocusSashEffect
import com.pokemon.battle.data.item.HeavyDutyBootsEffect
import com.pokemon.battle.data.item.LeftoversEffect
import com.pokemon.battle.data.item.LifeOrbEffect
import com.pokemon.battle.data.item.RedCardEffect
import com.pokemon.battle.data.item.RockyHelmetEffect
import com.pokemon.battle.data.item.SitrusBerryEffect
import com.pokemon.battle.data.item.WeaknessPolicyEffect
import com.pokemon.battle.engine.Registries
import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.engine.item.ItemRegistry

/**
 * The Gen V [Registries] bundle — all currently-modelled items and abilities
 * registered. Most callers use this. Gen-specific variants (GenIVRegistries,
 * GenVIRegistries) are a straightforward extension: a different effect list.
 *
 * Lives in `:data` because that's where the catalog entries are; the bundle type
 * itself lives in `:engine` so phases can reference it without inverting the
 * module dependency.
 */
val GenVRegistries: Registries =
    run {
        val abilities =
            AbilityRegistry(
                listOf(
                    IntimidateEffect,
                    DrizzleEffect,
                    DroughtEffect,
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
                    NaturalCureEffect,
                ),
            )
        val items =
            ItemRegistry(
                effects =
                    listOf(
                        LeftoversEffect,
                        FocusSashEffect,
                        LifeOrbEffect,
                        ChoiceBandEffect,
                        ChoiceSpecsEffect,
                        ChoiceScarfEffect,
                        EvioliteEffect,
                        SitrusBerryEffect,
                        RedCardEffect,
                        HeavyDutyBootsEffect,
                        RockyHelmetEffect,
                        WeaknessPolicyEffect,
                    ),
                abilities = abilities,
            )
        Registries(items = items, abilities = abilities)
    }

/**
 * The Gen IV [Registries] bundle — a strict subset of [GenVRegistries] dropping
 * effects introduced in Gen V or later. Built from Smogon-style generational
 * data (diary 087): items / abilities present in Gen IV metagames, excluding
 * anything that debuted afterward.
 *
 * Dropped items (Gen V+): Eviolite (Gen 5), Rocky Helmet (Gen 5), Red Card
 * (Gen 5), Weakness Policy (Gen 6), Heavy-Duty Boots (Gen 8).
 * Dropped abilities: Sand Rush (Gen 5), Sand Force (Gen 5), Emergency Exit (Gen 7).
 *
 * Kept: everything introduced in Gen 4 or earlier, plus Sturdy (our
 * implementation matches Gen 5+'s "survive from full HP" behavior — strictly
 * speaking wrong for Gen 4, but the inconsistency is noted in diary 087 and
 * kept pending a gen-specific `SturdyEffect` implementation).
 *
 * Not a pre-shipped production bundle — a *forcing function* to exercise the
 * "swap the registry, everything else compiles" claim from diary 071.
 */
val GenIVRegistries: Registries =
    run {
        val abilities =
            AbilityRegistry(
                listOf(
                    IntimidateEffect,
                    DrizzleEffect,
                    DroughtEffect,
                    LevitateEffect,
                    SandVeilEffect,
                    SnowCloakEffect,
                    IceBodyEffect,
                    BlazeEffect,
                    OvergrowEffect,
                    TorrentEffect,
                    KlutzEffect,
                    SturdyEffect,
                    NaturalCureEffect,
                ),
            )
        val items =
            ItemRegistry(
                effects =
                    listOf(
                        LeftoversEffect,
                        FocusSashEffect,
                        LifeOrbEffect,
                        ChoiceBandEffect,
                        ChoiceSpecsEffect,
                        ChoiceScarfEffect,
                        SitrusBerryEffect,
                    ),
                abilities = abilities,
            )
        Registries(items = items, abilities = abilities)
    }
