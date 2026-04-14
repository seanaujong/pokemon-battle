package com.pokemon.battle

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.serialization.BattleEventJson
import com.pokemon.battle.engine.serialization.TurnChoicesJson
import com.pokemon.battle.engine.serialization.toJson
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
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
 * Validates that every [BattleEvent] can round-trip through JSON via the
 * [BattleEventJson] DTO layer (diary 060). Domain events are *not* serializable
 * directly; the DTO is the on-disk contract.
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
                    MoveOrderPhase(GenVRegistries),
                    MoveExecutionPhase(GenVRegistries, roll = { 100 }),
                    EndOfTurnPhase(GenVRegistries),
                ),
            )
        val events: List<BattleEvent> = pipeline.resolveToCompletion(initial, choices).events

        assertTrue(events.isNotEmpty(), "Battle should produce at least one event")

        // Convert domain → DTO, serialize, deserialize, convert DTO → domain.
        val serializer = ListSerializer(BattleEventJson.serializer())
        val encoded = json.encodeToString(serializer, events.map { it.toJson() })
        val decoded: List<BattleEventJson> = json.decodeFromString(serializer, encoded)
        val roundTripped: List<BattleEvent> = decoded.map { it.toDomain() }

        assertEquals(events, roundTripped, "Round-tripped events should equal originals")
    }

    @Test
    fun `turn choices round-trip through JSON`() {
        val choices =
            TurnChoices(
                mapOf(
                    Slot.p1() to TurnChoice.UseMove(flamethrower, switchTo = 1),
                    Slot.p2() to TurnChoice.Switch(benchIndex = 0),
                ),
            )

        val encoded = json.encodeToString(TurnChoicesJson.serializer(), choices.toJson())
        val decoded = json.decodeFromString(TurnChoicesJson.serializer(), encoded).toDomain()

        assertEquals(choices, decoded, "Round-tripped choices should equal originals")
    }
}
