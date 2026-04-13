package com.pokemon.battle

import com.pokemon.battle.ai.*
import com.pokemon.battle.data.*
import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*
import com.pokemon.battle.phase.*
import com.pokemon.battle.loop.*
import com.pokemon.battle.render.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AITest {

    private val pokedex = Pokedex.loadFromClasspath()

    private val tackle = MoveDex.TACKLE
    private val flamethrower = MoveDex.FLAMETHROWER
    private val thunderbolt = MoveDex.THUNDERBOLT
    private val iceBeam = MoveDex.ICE_BEAM
    private val earthquake = MoveDex.EARTHQUAKE
    private val swordsDance = MoveDex.SWORDS_DANCE

    // --- RandomAI ---

    @Test
    fun `RandomAI produces valid choices for all slots`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state = BattleState.singles(
            PokemonState(charizard, currentHp = charizard.maxHp),
            PokemonState(venusaur, currentHp = venusaur.maxHp)
        )

        val ai = RandomAI(
            movePools = mapOf(
                Slot.p1() to listOf(flamethrower, thunderbolt),
                Slot.p2() to listOf(earthquake, swordsDance)
            ),
            random = java.util.Random(42) // deterministic
        )

        val choices = ai.getChoices(state)

        // Both slots should have a UseMove choice
        val p1Choice = choices.choiceFor(Slot.p1())
        val p2Choice = choices.choiceFor(Slot.p2())
        assertTrue(p1Choice is TurnChoice.UseMove)
        assertTrue(p2Choice is TurnChoice.UseMove)

        // Moves should be from the respective pools
        assertTrue((p1Choice as TurnChoice.UseMove).move in listOf(flamethrower, thunderbolt))
        assertTrue((p2Choice as TurnChoice.UseMove).move in listOf(earthquake, swordsDance))
    }

    @Test
    fun `RandomAI skips fainted slots`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state = BattleState.singles(
            PokemonState(charizard, currentHp = 0), // fainted
            PokemonState(venusaur, currentHp = venusaur.maxHp)
        )

        val ai = RandomAI(
            movePools = mapOf(
                Slot.p1() to listOf(flamethrower),
                Slot.p2() to listOf(earthquake)
            )
        )

        val choices = ai.getChoices(state)
        assertEquals(null, choices.choiceFor(Slot.p1()), "Fainted slot should have no choice")
        assertTrue(choices.choiceFor(Slot.p2()) is TurnChoice.UseMove)
    }

    // --- TypeAI ---

    @Test
    fun `TypeAI picks super-effective move over neutral`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state = BattleState.singles(
            PokemonState(charizard, currentHp = charizard.maxHp),
            PokemonState(venusaur, currentHp = venusaur.maxHp)
        )

        // Charizard has Flamethrower (super-effective vs Grass) and Thunderbolt (neutral vs Grass/Poison)
        val ai = TypeAI(movePools = mapOf(
            Slot.p1() to listOf(thunderbolt, flamethrower),
            Slot.p2() to listOf(earthquake)
        ))

        val choices = ai.getChoices(state)
        val p1Move = (choices.choiceFor(Slot.p1()) as TurnChoice.UseMove).move

        assertEquals("Flamethrower", p1Move.name, "Should pick super-effective Flamethrower over neutral Thunderbolt")
    }

    @Test
    fun `TypeAI considers STAB`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val snorlax = Pokemon(pokedex["Snorlax"]!!, level = 50) // Normal type, neutral to both Fire and Electric

        val state = BattleState.singles(
            PokemonState(charizard, currentHp = charizard.maxHp),
            PokemonState(snorlax, currentHp = snorlax.maxHp)
        )

        // Both neutral effectiveness, but Flamethrower gets STAB (Fire on Fire-type Charizard)
        // Flamethrower: 1.0 * 1.5 * 90 = 135
        // Thunderbolt:  1.0 * 1.0 * 90 = 90
        val ai = TypeAI(movePools = mapOf(
            Slot.p1() to listOf(thunderbolt, flamethrower),
            Slot.p2() to listOf(tackle)
        ))

        val choices = ai.getChoices(state)
        val p1Move = (choices.choiceFor(Slot.p1()) as TurnChoice.UseMove).move

        assertEquals("Flamethrower", p1Move.name, "Should pick STAB Flamethrower over non-STAB Thunderbolt")
    }

    @Test
    fun `TypeAI picks highest power when effectiveness is equal`() {
        val pikachu = Pokemon(pokedex["Pikachu"]!!, level = 50)
        val gyarados = Pokemon(pokedex["Gyarados"]!!, level = 50) // Water/Flying, 4x weak to Electric

        val state = BattleState.singles(
            PokemonState(pikachu, currentHp = pikachu.maxHp),
            PokemonState(gyarados, currentHp = gyarados.maxHp)
        )

        // Both Thunderbolt (90) and a weaker electric move would be super-effective
        // Thunderbolt: 4.0 * 1.5 (STAB) * 90 = 540
        // Tackle: 1.0 * 1.0 * 40 = 40
        val ai = TypeAI(movePools = mapOf(
            Slot.p1() to listOf(tackle, thunderbolt),
            Slot.p2() to listOf(tackle)
        ))

        val choices = ai.getChoices(state)
        val p1Move = (choices.choiceFor(Slot.p1()) as TurnChoice.UseMove).move

        assertEquals("Thunderbolt", p1Move.name, "Should pick 4x effective STAB Thunderbolt")
    }

    @Test
    fun `TypeAI falls back to first move when only status moves available`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state = BattleState.singles(
            PokemonState(charizard, currentHp = charizard.maxHp),
            PokemonState(venusaur, currentHp = venusaur.maxHp)
        )

        val ai = TypeAI(movePools = mapOf(
            Slot.p1() to listOf(swordsDance), // only status move
            Slot.p2() to listOf(tackle)
        ))

        val choices = ai.getChoices(state)
        val p1Move = (choices.choiceFor(Slot.p1()) as TurnChoice.UseMove).move

        assertEquals("Swords Dance", p1Move.name, "Should fall back to the only available move")
    }

    // --- Integration: AI vs AI battle ---

    @Test
    fun `TypeAI vs RandomAI produces a complete battle`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val garchomp = Pokemon(pokedex["Garchomp"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val initialState = BattleState.singles(
            PokemonState(charizard, currentHp = charizard.maxHp),
            PokemonState(venusaur, currentHp = venusaur.maxHp),
            p1Bench = listOf(PokemonState(garchomp, currentHp = garchomp.maxHp)),
            p2Bench = listOf(PokemonState(blastoise, currentHp = blastoise.maxHp))
        )

        val p1Moves = mapOf(
            Slot.p1() to listOf(flamethrower, thunderbolt, earthquake, iceBeam)
        )
        val p2Moves = mapOf(
            Slot.p2() to listOf(MoveDex.SLUDGE_BOMB, earthquake, tackle, swordsDance)
        )

        val typeAI = TypeAI(movePools = p1Moves)
        val randomAI = RandomAI(movePools = p2Moves, random = java.util.Random(123))

        val pipeline = TurnPipeline(listOf(
            MoveOrderPhase(), SwitchPhase(),
            MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false }),
            EndOfTurnPhase()
        ))

        val result = BattleLoop(
            pipeline = pipeline,
            choiceProvider = { state ->
                // Merge choices from both AIs
                val p1Choices = typeAI.getChoices(state)
                val p2Choices = randomAI.getChoices(state)
                TurnChoices(p1Choices.choices + p2Choices.choices)
            },
            faintReplacementProvider = { state, slot ->
                if (slot.side == Side.SIDE_1) typeAI.getReplacement(state, slot)
                else randomAI.getReplacement(state, slot)
            },
            maxTurns = 20
        ).run(initialState)

        assertTrue(result.turnHistory.isNotEmpty(), "Should have at least one turn")
        assertTrue(result.winner != null, "Someone should win within 20 turns")

        // Print the battle
        println("\n=== TYPE AI vs RANDOM AI ===")
        renderBattle(result, initialState).forEach(::println)
        println("=== END ===\n")
    }
}
