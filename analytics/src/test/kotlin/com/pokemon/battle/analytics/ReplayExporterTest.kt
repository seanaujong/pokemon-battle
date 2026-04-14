package com.pokemon.battle.analytics

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.serialization.BattleEventJson
import com.pokemon.battle.loop.BattleLoop
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Species
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayExporterTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val fixedRoll: (IntRange) -> Int = { 100 }
    private val noChance: ChanceCheck = { _, _ -> false }

    private fun pipeline() =
        TurnPipeline(
            listOf(
                MoveOrderPhase(GenVRegistries),
                SwitchPhase(GenVRegistries),
                MoveExecutionPhase(GenVRegistries, roll = fixedRoll, chanceCheck = noChance),
                EndOfTurnPhase(GenVRegistries),
            ),
        )

    private fun pokemon(species: Species) = PokemonState(Pokemon(species, level = 50), currentHp = Pokemon(species, level = 50).maxHp)

    // Decoder matched to ReplayExporter's encoder (same classDiscriminator).
    private val decoder =
        Json {
            prettyPrint = true
            classDiscriminator = "type"
        }

    // Mainline Pokemon mechanics — reachable in normal play

    @Test
    fun `export contains type discriminators and round-trips to original events`() {
        val charizard = pokemon(pokedex.getValue("Charizard"))
        val squirtle = pokemon(pokedex.getValue("Squirtle")).copy(currentHp = 1)

        val state = BattleState.singles(charizard, squirtle)

        val loop =
            BattleLoop(
                pipeline = pipeline(),
                choiceProvider = {
                    TurnChoices.singles(
                        TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                        TurnChoice.UseMove(MoveDex.TACKLE),
                    )
                },
                faintReplacementProvider = { _, _ -> 0 },
            )

        val result = loop.run(state)
        val json = ReplayExporter.toJson(result)

        // Pretty output should mention the discriminator and at least one known variant.
        assertTrue(json.contains("\"type\""), "JSON should use 'type' discriminator")
        assertTrue(
            json.contains("MoveAttemptedJson"),
            "JSON should include a MoveAttempted event under its DTO name",
        )

        // Decode back to DTOs then down to the domain events, and compare to the
        // originals. The engine's own `toJson` is the inverse we're round-tripping.
        val originals: List<BattleEvent> =
            result.turnHistory.flatMap { it.events + it.replacementEvents }
        val decoded =
            decoder.decodeFromString(ListSerializer(BattleEventJson.serializer()), json)
                .map { it.toDomain() }

        assertEquals(originals, decoded)
    }

    // Extensibility / corner cases
    //
    // ReplayExporter deliberately flattens turn boundaries — the on-disk format is a
    // flat event stream. This is a format choice, not an engine constraint; a
    // future "turn-scoped" export can sit alongside without changing `BattleEvent`.
}
