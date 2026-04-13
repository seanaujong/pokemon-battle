package com.pokemon.battle

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.GenVDamageCalculator
import com.pokemon.battle.engine.weatherDamageModifier
import com.pokemon.battle.gen.simplified.SimplifiedDamageCalculator
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.Weather
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeatherDamageBoostTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val fixedRoll: (IntRange) -> Int = { 100 }

    // ============================================================
    // Mainline Pokemon mechanics — Gen 2+ weather damage boost.
    // Rain: Water 1.5x, Fire 0.5x. Sun: Fire 1.5x, Water 0.5x.
    // ============================================================

    // --- Modifier function (exact values) ---

    @Test
    fun `weather damage modifier returns exact multipliers`() {
        assertEquals(1.5, weatherDamageModifier(Weather.RAIN, Type.WATER))
        assertEquals(0.5, weatherDamageModifier(Weather.RAIN, Type.FIRE))
        assertEquals(1.5, weatherDamageModifier(Weather.SUN, Type.FIRE))
        assertEquals(0.5, weatherDamageModifier(Weather.SUN, Type.WATER))

        // Non-matching types pass through
        assertEquals(1.0, weatherDamageModifier(Weather.RAIN, Type.ELECTRIC))
        assertEquals(1.0, weatherDamageModifier(Weather.SUN, Type.GRASS))

        // Sandstorm/Hail don't modify damage
        assertEquals(1.0, weatherDamageModifier(Weather.SANDSTORM, Type.FIRE))
        assertEquals(1.0, weatherDamageModifier(Weather.HAIL, Type.WATER))

        // No weather passes through
        assertEquals(1.0, weatherDamageModifier(null, Type.FIRE))
    }

    // --- Pipeline integration (relationships) ---

    @Test
    fun `Rain reduces Fire move damage`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)
        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(blastoise, currentHp = blastoise.maxHp)

        val clear = calc(attacker, defender, MoveDex.FLAMETHROWER, null).damage
        val rain = calc(attacker, defender, MoveDex.FLAMETHROWER, Weather.RAIN).damage

        assertTrue(rain < clear, "Fire in Rain should deal less: rain=$rain clear=$clear")
    }

    @Test
    fun `Sun boosts Fire move damage`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)
        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(blastoise, currentHp = blastoise.maxHp)

        val clear = calc(attacker, defender, MoveDex.FLAMETHROWER, null).damage
        val sun = calc(attacker, defender, MoveDex.FLAMETHROWER, Weather.SUN).damage

        assertTrue(sun > clear, "Fire in Sun should deal more: sun=$sun clear=$clear")
    }

    @Test
    fun `Sandstorm and Hail do not modify Fire move damage`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)
        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(blastoise, currentHp = blastoise.maxHp)

        val clear = calc(attacker, defender, MoveDex.FLAMETHROWER, null).damage
        val sand = calc(attacker, defender, MoveDex.FLAMETHROWER, Weather.SANDSTORM).damage
        val hail = calc(attacker, defender, MoveDex.FLAMETHROWER, Weather.HAIL).damage

        assertEquals(clear, sand, "Sandstorm should not modify move damage")
        assertEquals(clear, hail, "Hail should not modify move damage")
    }

    @Test
    fun `non-Fire non-Water moves are unaffected by weather`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)
        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(blastoise, currentHp = blastoise.maxHp)

        val clear = calc(attacker, defender, MoveDex.THUNDERBOLT, null).damage
        val rain = calc(attacker, defender, MoveDex.THUNDERBOLT, Weather.RAIN).damage
        val sun = calc(attacker, defender, MoveDex.THUNDERBOLT, Weather.SUN).damage

        assertEquals(clear, rain, "Electric should not be affected by Rain")
        assertEquals(clear, sun, "Electric should not be affected by Sun")
    }

    @Test
    fun `Simplified calculator ignores weather`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)
        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(blastoise, currentHp = blastoise.maxHp)

        val clear =
            SimplifiedDamageCalculator()
                .calculate(attacker, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null)
        val rain =
            SimplifiedDamageCalculator()
                .calculate(attacker, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, Weather.RAIN)

        assertEquals(clear.damage, rain.damage, "Simplified gen ignores weather")
    }

    private fun calc(
        attacker: PokemonState,
        defender: PokemonState,
        move: com.pokemon.battle.model.Move,
        weather: Weather?,
    ) = GenVDamageCalculator().calculate(attacker, defender, move, fixedRoll, 1.0, false, weather)
}
