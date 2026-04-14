package com.pokemon.battle

import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Species
import com.pokemon.battle.model.Type
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates that every [BattleEvent] subclass (and all referenced model types)
 * can round-trip through JSON via kotlinx-serialization's sealed polymorphism.
 *
 * This is the integration-level unlock described in diary 050: events are
 * the project's universal data asset; making them serializable enables
 * analytics, replay, logging, and JSON wire formats.
 */
class EventSerializationTest {
    private val charizardSpecies =
        Species(
            name = "Charizard",
            types = listOf(Type.FIRE, Type.FLYING),
            baseHp = 78,
            baseAttack = 84,
            baseDefense = 78,
            baseSpecialAttack = 109,
            baseSpecialDefense = 85,
            baseSpeed = 100,
        )

    private val venusaurSpecies =
        Species(
            name = "Venusaur",
            types = listOf(Type.GRASS, Type.POISON),
            baseHp = 80,
            baseAttack = 82,
            baseDefense = 83,
            baseSpecialAttack = 100,
            baseSpecialDefense = 100,
            baseSpeed = 80,
        )

    private val flamethrower =
        Move(
            name = "Flamethrower",
            type = Type.FIRE,
            category = MoveCategory.SPECIAL,
            power = 90,
        )

    private val sludgeBomb =
        Move(
            name = "Sludge Bomb",
            type = Type.POISON,
            category = MoveCategory.SPECIAL,
            power = 90,
        )

    private val json = Json { classDiscriminator = "type" }

    @Test
    fun `event list round-trips through JSON`() {
        val charizard = PokemonState(Pokemon(charizardSpecies, level = 50), currentHp = 153)
        val venusaur = PokemonState(Pokemon(venusaurSpecies, level = 50), currentHp = 130)

        val initial = BattleState.singles(charizard, venusaur)
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(flamethrower),
                TurnChoice.UseMove(sludgeBomb),
            )
        val pipeline =
            TurnPipeline(
                listOf(
                    MoveOrderPhase(),
                    MoveExecutionPhase(roll = { 100 }),
                    EndOfTurnPhase(),
                ),
            )
        val events: List<BattleEvent> = pipeline.resolveToCompletion(initial, choices).events

        assertTrue(events.isNotEmpty(), "Battle should produce at least one event")

        val serializer = ListSerializer(BattleEvent.serializer())
        val encoded = json.encodeToString(serializer, events)
        val decoded = json.decodeFromString(serializer, encoded)

        assertEquals(events, decoded, "Round-tripped events should equal originals")
    }
}
