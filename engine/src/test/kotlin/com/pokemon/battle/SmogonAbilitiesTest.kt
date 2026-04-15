package com.pokemon.battle

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.AbilityDamage
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.WeatherSet
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Weather
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the Gen 5 OU abilities added to support Smogon set play:
 * Iron Barbs, Rough Skin, Sand Stream, Technician.
 */
class SmogonAbilitiesTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val noChance: ChanceCheck = { _, _ -> false }
    private val fixedRoll: (IntRange) -> Int = { 100 }

    private fun pipeline() =
        TurnPipeline(
            listOf(
                MoveOrderPhase(GenVRegistries),
                SwitchPhase(GenVRegistries),
                MoveExecutionPhase(GenVRegistries, roll = fixedRoll, chanceCheck = noChance),
                EndOfTurnPhase(GenVRegistries),
            ),
        )

    // ============================================================
    // Mainline Pokemon mechanics — reachable in normal play
    // ============================================================

    @Test
    fun `Iron Barbs deals 1 over 8 of attacker's max HP as recoil on contact move`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp, ability = Ability.IRON_BARBS)
        val state = BattleState.singles(attacker, defender)

        // Tackle is contact — Iron Barbs fires.
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.TACKLE),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val recoil = result.events.filterIsInstance<AbilityDamage>().filter { it.ability == Ability.IRON_BARBS }
        assertEquals(1, recoil.size, "Iron Barbs should fire once on a contact hit")
        assertEquals(attacker.maxHp / 8, recoil.single().amount, "Iron Barbs recoil is 1/8 attacker max HP")
    }

    @Test
    fun `Iron Barbs does not fire on non-contact move (Flamethrower)`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp, ability = Ability.IRON_BARBS)
        val state = BattleState.singles(attacker, defender)

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val recoil = result.events.filterIsInstance<AbilityDamage>().filter { it.ability == Ability.IRON_BARBS }
        assertTrue(recoil.isEmpty(), "Iron Barbs should not fire on a non-contact move")
    }

    @Test
    fun `Rough Skin deals 1 over 8 contact recoil just like Iron Barbs`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp, ability = Ability.ROUGH_SKIN)
        val state = BattleState.singles(attacker, defender)

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.TACKLE),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val recoil = result.events.filterIsInstance<AbilityDamage>().filter { it.ability == Ability.ROUGH_SKIN }
        assertEquals(1, recoil.size, "Rough Skin should fire once on a contact hit")
        assertEquals(attacker.maxHp / 8, recoil.single().amount, "Rough Skin recoil is 1/8 attacker max HP")
    }

    @Test
    fun `Sand Stream sets sandstorm for 5 turns on switch-in`() {
        // Switch in via a pre-selected switch: attacker starts with a placeholder,
        // the holder on the bench has Sand Stream and swaps in turn 1.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val p1 = PokemonState(charizard, currentHp = charizard.maxHp)
        val p2Active = PokemonState(venusaur, currentHp = venusaur.maxHp)
        val p2Bench = PokemonState(blastoise, currentHp = blastoise.maxHp, ability = Ability.SAND_STREAM)

        val state = BattleState.singles(p1, p2Active, p2Bench = listOf(p2Bench))

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.Switch(benchIndex = 0),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val weatherSet = result.events.filterIsInstance<WeatherSet>().filter { it.weather == Weather.SANDSTORM }
        assertEquals(1, weatherSet.size, "Sand Stream should emit exactly one WeatherSet(SANDSTORM, 5)")
        assertEquals(5, weatherSet.single().turnsRemaining)
    }

    @Test
    fun `Technician boosts damage of base-power-60 move`() {
        val scyther = pokedex["Scizor"] ?: pokedex["Charizard"]!!
        val attackerSpecies = Pokemon(scyther, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // Without Technician
        val attackerNoTech = PokemonState(attackerSpecies, currentHp = attackerSpecies.maxHp)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp)
        val stateNoTech = BattleState.singles(attackerNoTech, defender)

        // With Technician
        val attackerTech = PokemonState(attackerSpecies, currentHp = attackerSpecies.maxHp, ability = Ability.TECHNICIAN)
        val stateTech = BattleState.singles(attackerTech, defender)

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.TACKLE),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val dmgNoTech =
            pipeline().resolveToCompletion(stateNoTech, choices)
                .events.filterIsInstance<DamageDealt>().first { it.amount > 0 }.amount
        val dmgTech =
            pipeline().resolveToCompletion(stateTech, choices)
                .events.filterIsInstance<DamageDealt>().first { it.amount > 0 }.amount

        assertNotEquals(dmgNoTech, dmgTech, "Technician should change damage output for a 40-BP move")
        // Technician is a 1.5x boost — exact floor-rounding matters, so just assert the relationship.
        assertTrue(dmgTech > dmgNoTech, "Technician boosts damage (1.5x) for base-power<=60 moves")
    }

    @Test
    fun `Technician does not boost damage of move over base power 60`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // Flamethrower has base power 90 — above the Technician threshold.
        val attackerNoTech = PokemonState(charizard, currentHp = charizard.maxHp)
        val attackerTech = PokemonState(charizard, currentHp = charizard.maxHp, ability = Ability.TECHNICIAN)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp)

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val dmgNoTech =
            pipeline().resolveToCompletion(BattleState.singles(attackerNoTech, defender), choices)
                .events.filterIsInstance<DamageDealt>().first { it.amount > 0 }.amount
        val dmgTech =
            pipeline().resolveToCompletion(BattleState.singles(attackerTech, defender), choices)
                .events.filterIsInstance<DamageDealt>().first { it.amount > 0 }.amount

        assertEquals(dmgNoTech, dmgTech, "Technician must not affect moves with base power > 60")
    }
}
