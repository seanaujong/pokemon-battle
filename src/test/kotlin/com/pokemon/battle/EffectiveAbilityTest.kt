package com.pokemon.battle

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.GenVDamageCalculator
import com.pokemon.battle.engine.ItemDamage
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EffectiveAbilityTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val noChance: ChanceCheck = { _, _ -> false }
    private val fixedRoll: (IntRange) -> Int = { 100 }

    private fun pipeline() =
        TurnPipeline(
            listOf(
                MoveOrderPhase(),
                SwitchPhase(),
                MoveExecutionPhase(roll = fixedRoll, chanceCheck = noChance),
                EndOfTurnPhase(),
            ),
        )

    // ============================================================
    // Mainline mechanics — via the effectiveAbility read seam.
    // These tests use abilityOverride directly; a future diary
    // will wire it to Gastro Acid / Neutralizing Gas / Mega forms.
    // ============================================================

    @Test
    fun `effectiveAbility falls back to base ability when override is null`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val state = PokemonState(charizard, currentHp = charizard.maxHp, ability = Ability.BLAZE)

        assertEquals(Ability.BLAZE, state.effectiveAbility)
    }

    @Test
    fun `effectiveAbility returns the override when set`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val state =
            PokemonState(
                charizard,
                currentHp = charizard.maxHp,
                ability = Ability.BLAZE,
                abilityOverride = Ability.KLUTZ,
            )

        assertEquals(Ability.KLUTZ, state.effectiveAbility)
    }

    @Test
    fun `Klutz via abilityOverride suppresses Life Orb damage boost`() {
        // Charizard's base ability is BLAZE, but mid-battle override says KLUTZ.
        // Life Orb should now be inert.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val normal =
            PokemonState(
                charizard,
                currentHp = charizard.maxHp,
                ability = Ability.BLAZE,
                item = Item.LIFE_ORB,
            )
        val overridden =
            PokemonState(
                charizard,
                currentHp = charizard.maxHp,
                ability = Ability.BLAZE,
                abilityOverride = Ability.KLUTZ,
                item = Item.LIFE_ORB,
            )
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp)

        val normalDmg =
            GenVDamageCalculator()
                .calculate(normal, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage
        val overriddenDmg =
            GenVDamageCalculator()
                .calculate(overridden, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage

        assertTrue(overriddenDmg < normalDmg, "KLUTZ override should disable Life Orb: override=$overriddenDmg normal=$normalDmg")
    }

    @Test
    fun `Klutz via abilityOverride suppresses Life Orb recoil`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(
                    charizard,
                    currentHp = charizard.maxHp,
                    ability = Ability.BLAZE,
                    abilityOverride = Ability.KLUTZ,
                    item = Item.LIFE_ORB,
                ),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)

        assertTrue(
            result.events.filterIsInstance<ItemDamage>().none { it.item == Item.LIFE_ORB },
            "Override-applied Klutz should disable Life Orb recoil",
        )
        // Damage still dealt (Flamethrower still hits, just without the boost)
        assertTrue(
            result.events.filterIsInstance<DamageDealt>().any { it.target == Slot.p2() && it.amount > 0 },
            "The attack still lands without the orb boost",
        )
    }
}
