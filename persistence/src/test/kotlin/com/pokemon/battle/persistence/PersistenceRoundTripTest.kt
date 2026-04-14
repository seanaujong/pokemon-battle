package com.pokemon.battle.persistence

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.MoveAttempted
import com.pokemon.battle.loop.BattleResult
import com.pokemon.battle.loop.TurnRecord
import com.pokemon.battle.model.Effectiveness
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Species
import com.pokemon.battle.model.Type
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PersistenceRoundTripTest {
    private val species =
        Species(
            name = "Testmon",
            types = listOf(Type.NORMAL),
            baseHp = 100,
            baseAttack = 80,
            baseDefense = 80,
            baseSpecialAttack = 80,
            baseSpecialDefense = 80,
            baseSpeed = 80,
        )
    private val tackle = Move("Tackle", Type.NORMAL, MoveCategory.PHYSICAL, 40)

    private fun stubResult(): BattleResult {
        val poke = PokemonState(Pokemon(species, level = 50), currentHp = 100)
        val state = BattleState.singles(poke, poke)
        val turn =
            TurnRecord(
                turnNumber = 1,
                events =
                    listOf(
                        MoveAttempted(Slot.p1(), tackle),
                        DamageDealt(Slot.p2(), amount = 42, effectiveness = Effectiveness.NEUTRAL),
                    ),
            )
        return BattleResult(winner = Side.SIDE_1, finalState = state, turnHistory = listOf(turn))
    }

    private fun stubMetadata() =
        BattleMetadata(
            battleId = "test-battle-42",
            startedAtEpochMs = 1000L,
            endedAtEpochMs = 2000L,
            formatTag = "test",
        )

    @Test
    fun `FileBattleRecorder writes a battle that BattleLoader reads back equal`() {
        val tempDir = Files.createTempDirectory("persistence-test-")
        try {
            val recorder = FileBattleRecorder(tempDir, prettyPrint = false)
            recorder.record(stubResult(), stubMetadata())

            val loaded = BattleLoader.loadAll(tempDir).toList()
            assertEquals(1, loaded.size, "exactly one battle file should have been written")

            val persisted = loaded.single()
            assertEquals(stubMetadata(), persisted.metadata)
            assertEquals(Side.SIDE_1, persisted.winner)
            assertEquals(1, persisted.turns.size)
            assertEquals(1, persisted.turns.single().turnNumber)
            assertEquals(2, persisted.turns.single().events.size, "one MoveAttempted + one DamageDealt")
        } finally {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `toPersisted drops finalState (events are the source of truth)`() {
        val persisted = toPersisted(stubResult(), stubMetadata())
        // PersistedBattle has no finalState field at all — this compiles means the test passes.
        // Asserting the turn shape is enough to confirm events round-tripped.
        assertEquals(1, persisted.turns.size)
    }

    @Test
    fun `BattleLoader on a missing directory returns empty sequence`() {
        val bogus = Files.createTempDirectory("persistence-test-bogus-").resolve("does-not-exist")
        val loaded = BattleLoader.loadAll(bogus).toList()
        assertEquals(emptyList(), loaded)
    }
}
