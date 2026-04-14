package com.pokemon.battle

import com.pokemon.battle.ai.RandomAI
import com.pokemon.battle.ai.SideProviders
import com.pokemon.battle.ai.SidedAI
import com.pokemon.battle.ai.TypeAI
import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.loop.BattleLoop
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

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )

        val ai =
            RandomAI(
                movePools =
                    mapOf(
                        "Charizard" to listOf(flamethrower, thunderbolt),
                        "Venusaur" to listOf(earthquake, swordsDance),
                    ),
                random = kotlin.random.Random(42),
            )

        val choices = ai.getChoices(state)
        val p1Choice = choices.choiceFor(Slot.p1())
        val p2Choice = choices.choiceFor(Slot.p2())
        assertTrue(p1Choice is TurnChoice.UseMove)
        assertTrue(p2Choice is TurnChoice.UseMove)
        assertTrue((p1Choice as TurnChoice.UseMove).move in listOf(flamethrower, thunderbolt))
        assertTrue((p2Choice as TurnChoice.UseMove).move in listOf(earthquake, swordsDance))
    }

    @Test
    fun `RandomAI skips fainted slots`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = 0),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )

        val ai =
            RandomAI(
                movePools =
                    mapOf(
                        "Charizard" to listOf(flamethrower),
                        "Venusaur" to listOf(earthquake),
                    ),
            )

        val choices = ai.getChoices(state)
        assertEquals(null, choices.choiceFor(Slot.p1()))
        assertTrue(choices.choiceFor(Slot.p2()) is TurnChoice.UseMove)
    }

    // --- TypeAI ---

    @Test
    fun `TypeAI picks super-effective move over neutral`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )

        val ai =
            TypeAI(
                movePools =
                    mapOf(
                        "Charizard" to listOf(thunderbolt, flamethrower),
                        "Venusaur" to listOf(earthquake),
                    ),
            )

        val choices = ai.getChoices(state)
        val p1Move = (choices.choiceFor(Slot.p1()) as TurnChoice.UseMove).move
        assertEquals("Flamethrower", p1Move.name)
    }

    @Test
    fun `TypeAI considers STAB`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val snorlax = Pokemon(pokedex["Snorlax"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(snorlax, currentHp = snorlax.maxHp),
            )

        val ai =
            TypeAI(
                movePools =
                    mapOf(
                        "Charizard" to listOf(thunderbolt, flamethrower),
                        "Snorlax" to listOf(tackle),
                    ),
            )

        val choices = ai.getChoices(state)
        val p1Move = (choices.choiceFor(Slot.p1()) as TurnChoice.UseMove).move
        assertEquals("Flamethrower", p1Move.name)
    }

    @Test
    fun `TypeAI picks highest power when effectiveness is equal`() {
        val pikachu = Pokemon(pokedex["Pikachu"]!!, level = 50)
        val gyarados = Pokemon(pokedex["Gyarados"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(pikachu, currentHp = pikachu.maxHp),
                PokemonState(gyarados, currentHp = gyarados.maxHp),
            )

        val ai =
            TypeAI(
                movePools =
                    mapOf(
                        "Pikachu" to listOf(tackle, thunderbolt),
                        "Gyarados" to listOf(tackle),
                    ),
            )

        val choices = ai.getChoices(state)
        val p1Move = (choices.choiceFor(Slot.p1()) as TurnChoice.UseMove).move
        assertEquals("Thunderbolt", p1Move.name)
    }

    @Test
    fun `TypeAI falls back to first move when only status moves available`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )

        val ai =
            TypeAI(
                movePools =
                    mapOf(
                        "Charizard" to listOf(swordsDance),
                        "Venusaur" to listOf(tackle),
                    ),
            )

        val choices = ai.getChoices(state)
        val p1Move = (choices.choiceFor(Slot.p1()) as TurnChoice.UseMove).move
        assertEquals("Swords Dance", p1Move.name)
    }

    // --- Integration: AI vs AI battle ---

    @Test
    fun `TypeAI vs RandomAI produces a complete battle`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val garchomp = Pokemon(pokedex["Garchomp"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val initialState =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                p1Bench = listOf(PokemonState(garchomp, currentHp = garchomp.maxHp)),
                p2Bench = listOf(PokemonState(blastoise, currentHp = blastoise.maxHp)),
            )

        val side1AI =
            TypeAI(
                movePools =
                    mapOf(
                        "Charizard" to listOf(flamethrower, thunderbolt, earthquake, iceBeam),
                        "Garchomp" to listOf(earthquake, iceBeam, flamethrower, swordsDance),
                    ),
            )
        val side2AI =
            RandomAI(
                movePools =
                    mapOf(
                        "Venusaur" to listOf(MoveDex.SLUDGE_BOMB, earthquake, tackle, swordsDance),
                        "Blastoise" to listOf(iceBeam, earthquake, tackle, MoveDex.SLUDGE_BOMB),
                    ),
                random = kotlin.random.Random(123),
            )

        val ai =
            SidedAI(
                side1 = SideProviders(side1AI, side1AI, side1AI),
                side2 = SideProviders(side2AI, side2AI),
            )

        val pipeline =
            TurnPipeline(
                listOf(
                    MoveOrderPhase(GenVRegistries),
                    SwitchPhase(GenVRegistries),
                    MoveExecutionPhase(GenVRegistries, roll = { 100 }, chanceCheck = { _, _ -> false }),
                    EndOfTurnPhase(GenVRegistries),
                ),
            )

        val result =
            BattleLoop(
                pipeline = pipeline,
                choiceProvider = ai,
                faintReplacementProvider = ai,
                maxTurns = 20,
            ).run(initialState)

        assertTrue(result.turnHistory.isNotEmpty())
        assertTrue(result.winner != null, "Someone should win within 20 turns")
    }
}
