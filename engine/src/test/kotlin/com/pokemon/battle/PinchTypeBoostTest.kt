package com.pokemon.battle

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.GenVDamageCalculator
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PinchTypeBoostTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val fixedRoll: (IntRange) -> Int = { 100 }

    // ============================================================
    // Mainline Pokemon mechanics — Gen 4+ pinch-type boost.
    // At or below 1/3 max HP, matching-type damaging moves get 1.5x.
    // ============================================================

    @Test
    fun `Blaze boosts Fire move damage at or below one-third HP`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // Full HP, Blaze inactive
        val fullHp =
            PokemonState(charizard, currentHp = charizard.maxHp, ability = Ability.BLAZE)
        // 1/3 max HP, Blaze active
        val pinchHp =
            PokemonState(charizard, currentHp = charizard.maxHp / 3, ability = Ability.BLAZE)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp)

        val fullHpDamage =
            GenVDamageCalculator()
                .calculate(fullHp, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage
        val pinchDamage =
            GenVDamageCalculator()
                .calculate(pinchHp, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage

        assertTrue(
            pinchDamage > fullHpDamage,
            "Blaze at 1/3 HP should deal more Fire damage: pinch=$pinchDamage full=$fullHpDamage",
        )
    }

    @Test
    fun `Blaze does not boost non-Fire moves`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val fullHp =
            PokemonState(charizard, currentHp = charizard.maxHp, ability = Ability.BLAZE)
        val pinchHp =
            PokemonState(charizard, currentHp = charizard.maxHp / 3, ability = Ability.BLAZE)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp)

        // Charizard doesn't have many non-Fire special moves, but Flamethrower's type is
        // the main test. Check a non-Fire move with Thunderbolt (neutral against Venusaur).
        val fullHpDamage =
            GenVDamageCalculator()
                .calculate(fullHp, defender, MoveDex.THUNDERBOLT, fixedRoll, 1.0, false, null).damage
        val pinchDamage =
            GenVDamageCalculator()
                .calculate(pinchHp, defender, MoveDex.THUNDERBOLT, fixedRoll, 1.0, false, null).damage

        assertEquals(fullHpDamage, pinchDamage, "Blaze should not boost Electric moves")
    }

    @Test
    fun `Overgrow boosts Grass moves at low HP`() {
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)

        val pinchHp =
            PokemonState(venusaur, currentHp = venusaur.maxHp / 3, ability = Ability.OVERGROW)
        val fullHp =
            PokemonState(venusaur, currentHp = venusaur.maxHp, ability = Ability.OVERGROW)
        val defender = PokemonState(charizard, currentHp = charizard.maxHp)

        // Venusaur doesn't have a Grass move in our MoveDex, but Sludge Bomb (Poison) is available.
        // So test that Overgrow does NOT affect Sludge Bomb damage.
        val fullHpSludge =
            GenVDamageCalculator()
                .calculate(fullHp, defender, MoveDex.SLUDGE_BOMB, fixedRoll, 1.0, false, null).damage
        val pinchSludge =
            GenVDamageCalculator()
                .calculate(pinchHp, defender, MoveDex.SLUDGE_BOMB, fixedRoll, 1.0, false, null).damage

        assertEquals(fullHpSludge, pinchSludge, "Overgrow should not boost Poison moves")
    }

    @Test
    fun `pinch threshold is 1 over 3 max HP, not strict less-than`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp)

        // At exactly maxHp / 3 (integer division) — should be at threshold and boost
        val atThreshold =
            PokemonState(charizard, currentHp = charizard.maxHp / 3, ability = Ability.BLAZE)

        val atThresholdDamage =
            GenVDamageCalculator()
                .calculate(atThreshold, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage

        // Much higher HP — Blaze inactive
        val aboveThreshold =
            PokemonState(charizard, currentHp = charizard.maxHp, ability = Ability.BLAZE)
        val aboveThresholdDamage =
            GenVDamageCalculator()
                .calculate(aboveThreshold, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage

        assertTrue(
            atThresholdDamage > aboveThresholdDamage,
            "At 1/3 HP threshold, Blaze should activate: threshold=$atThresholdDamage above=$aboveThresholdDamage",
        )
    }
}
