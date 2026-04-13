package com.pokemon.battle

import com.pokemon.battle.data.*
import com.pokemon.battle.engine.*
import com.pokemon.battle.model.*
import com.pokemon.battle.phase.*
import com.pokemon.battle.render.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeOverrideTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val fixedRoll: (IntRange) -> Int = { 100 }
    private val noChance: ChanceCheck = { _, _ -> false }

    // --- Camomons: type set at construction ---

    @Test
    fun `Camomons type override changes effectiveness`() {
        // Snorlax (Normal) overridden to Fire type — now weak to Water
        val snorlax = Pokemon(pokedex["Snorlax"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(blastoise, currentHp = blastoise.maxHp),
                PokemonState(snorlax, currentHp = snorlax.maxHp, typeOverride = listOf(Type.FIRE)),
            )

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.ICE_BEAM), // Ice vs Fire = not very effective
                TurnChoice.UseMove(MoveDex.TACKLE),
            )

        val phase = MoveExecutionPhase(roll = fixedRoll, chanceCheck = noChance)
        val events = phase.resolve(state, choices)

        val damage = events.filterIsInstance<DamageDealt>().first { it.target == Slot.p2() }
        assertEquals(
            Effectiveness.NOT_VERY_EFFECTIVE,
            damage.effectiveness,
            "Ice vs Fire-type Snorlax should be not very effective",
        )
    }

    @Test
    fun `Camomons STAB changes with type override`() {
        // Pikachu (Electric) overridden to Fire — now gets STAB on Flamethrower
        val pikachu = Pokemon(pokedex["Pikachu"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val withOverride =
            PokemonState(
                pikachu,
                currentHp = pikachu.maxHp,
                typeOverride = listOf(Type.FIRE),
            )
        val withoutOverride = PokemonState(pikachu, currentHp = pikachu.maxHp)

        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp)

        val damageWithSTAB = calculateDamage(withOverride, defender, MoveDex.FLAMETHROWER, fixedRoll)
        val damageWithoutSTAB = calculateDamage(withoutOverride, defender, MoveDex.FLAMETHROWER, fixedRoll)

        assertTrue(
            damageWithSTAB.damage > damageWithoutSTAB.damage,
            "Fire-type Pikachu should get STAB on Flamethrower (${damageWithSTAB.damage} vs ${damageWithoutSTAB.damage})",
        )
    }

    // --- Mid-battle type change (Terastallization) ---

    @Test
    fun `TypeChanged event overrides types mid-battle`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(charizard, currentHp = charizard.maxHp),
            )

        // Before: Fire/Flying
        assertEquals(listOf(Type.FIRE, Type.FLYING), state.pokemonFor(Slot.p1()).effectiveTypes)

        // Apply TypeChanged: become pure Water
        val event = TypeChanged(Slot.p1(), listOf(Type.WATER))
        val newState = event.apply(state)

        assertEquals(listOf(Type.WATER), newState.pokemonFor(Slot.p1()).effectiveTypes)
        // Species types unchanged
        assertEquals(listOf(Type.FIRE, Type.FLYING), newState.pokemonFor(Slot.p1()).pokemon.species.types)
    }

    @Test
    fun `type override affects weather immunity`() {
        // Normal type overridden to Ground — should be immune to sandstorm
        val normalSpecies = Species("Normal", listOf(Type.NORMAL), 80, 100, 80, 80, 80, 80)
        val state =
            BattleState.singles(
                PokemonState(
                    Pokemon(normalSpecies, 50),
                    currentHp = 155,
                    typeOverride = listOf(Type.GROUND),
                ),
                PokemonState(Pokemon(normalSpecies, 50), currentHp = 155),
                field = FieldState(weather = Weather.SANDSTORM, weatherTurnsRemaining = 3),
            )

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.TACKLE),
                TurnChoice.UseMove(MoveDex.TACKLE),
            )

        val events = EndOfTurnPhase().resolve(state, choices)
        val weatherDamage = events.filterIsInstance<WeatherDamage>()

        // P1 (Ground override) should be immune, P2 (Normal) takes damage
        assertEquals(1, weatherDamage.size)
        assertEquals(
            Slot.p2(),
            weatherDamage[0].target,
            "Only the Normal-type should take sandstorm damage",
        )
    }

    // --- Renderer ---

    @Test
    fun `TypeChanged renders correctly`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(charizard, currentHp = charizard.maxHp),
            )

        val event = TypeChanged(Slot.p1(), listOf(Type.WATER))
        val stateAfter = event.apply(state)
        val lines = TextRenderer.render(event, state, stateAfter)

        assertEquals(listOf("Charizard's type changed to Water!"), lines)
    }

    // --- Default behavior unchanged ---

    @Test
    fun `null typeOverride uses species types`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val state = PokemonState(charizard, currentHp = charizard.maxHp)

        assertEquals(listOf(Type.FIRE, Type.FLYING), state.effectiveTypes)
        assertEquals(null, state.typeOverride)
    }
}
