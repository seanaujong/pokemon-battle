package com.pokemon.battle.data

import com.pokemon.battle.data.ability.BlazeEffect
import com.pokemon.battle.data.ability.DrizzleEffect
import com.pokemon.battle.data.ability.DroughtEffect
import com.pokemon.battle.data.ability.EmergencyExitEffect
import com.pokemon.battle.data.ability.IceBodyEffect
import com.pokemon.battle.data.ability.IntimidateEffect
import com.pokemon.battle.data.ability.KlutzEffect
import com.pokemon.battle.data.ability.LevitateEffect
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
