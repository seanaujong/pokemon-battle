package com.pokemon.battle.server

import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.serialization.toJson
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.Slot
import com.pokemon.battle.server.protocol.ChoiceSubmit
import com.pokemon.battle.server.protocol.ClientMessage
import com.pokemon.battle.server.protocol.ErrorMessage
import com.pokemon.battle.server.protocol.FaintReplacement
import com.pokemon.battle.server.protocol.Ready
import com.pokemon.battle.server.protocol.Result
import com.pokemon.battle.server.protocol.ServerMessage
import com.pokemon.battle.server.protocol.TeamSet
import com.pokemon.battle.server.protocol.TurnEvents
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerSessionTest {
    private val json = Json { classDiscriminator = "type" }

    private val side1Team =
        """
        Charizard
        Ability: Blaze
        Level: 100
        - Flamethrower
        """.trimIndent()

    private val side2Team =
        """
        Venusaur
        Ability: Overgrow
        Level: 100
        - Sludge Bomb
        """.trimIndent()

    @Test
    fun `end-to-end battle via JSONL emits a Result`() {
        val inputLines = mutableListOf<String>()
        inputLines.add(encode(TeamSet(side = com.pokemon.battle.model.Side.SIDE_1, team = side1Team)))
        inputLines.add(encode(TeamSet(side = com.pokemon.battle.model.Side.SIDE_2, team = side2Team)))
        val flamethrower = Move("Flamethrower", com.pokemon.battle.model.Type.FIRE, com.pokemon.battle.model.MoveCategory.SPECIAL, 90)
        val sludgeBomb = Move("Sludge Bomb", com.pokemon.battle.model.Type.POISON, com.pokemon.battle.model.MoveCategory.SPECIAL, 90)
        val choicesPerTurn =
            encode(
                ChoiceSubmit(
                    choices =
                        TurnChoices(
                            mapOf(
                                Slot.p1() to TurnChoice.UseMove(flamethrower),
                                Slot.p2() to TurnChoice.UseMove(sludgeBomb),
                            ),
                        ).toJson(),
                ),
            )
        // Pre-script more choice submits than any reasonable battle will need.
        repeat(50) { inputLines.add(choicesPerTurn) }
        // And some faint replacements in case the battle tries to prompt (empty bench → never asked).
        repeat(10) { inputLines.add(encode(FaintReplacement(slot = Slot.p1(), benchIndex = 0))) }

        val input = BufferedReader(StringReader(inputLines.joinToString("\n") + "\n"))
        val outputWriter = StringWriter()
        val output = PrintWriter(outputWriter, true)

        ServerSession(input, output, maxTurns = 20).run()

        val messages = outputWriter.toString().lines().filter { it.isNotBlank() }.map { decode(it) }
        assertTrue(messages.isNotEmpty(), "server emitted no messages")
        assertTrue(messages.first() is Ready, "first message should be Ready, got ${messages.first()::class.simpleName}")
        val result = messages.lastOrNull { it is Result } as Result?
        assertNotNull(result, "server never emitted a Result")
        assertTrue(messages.any { it is TurnEvents }, "no TurnEvents emitted")
    }

    @Test
    fun `mismatched protocolVersion produces an error message`() {
        val mismatched =
            encode(
                TeamSet(
                    side = com.pokemon.battle.model.Side.SIDE_1,
                    team = side1Team,
                    protocolVersion = 999,
                ),
            )
        val input = BufferedReader(StringReader(mismatched + "\n"))
        val output = PrintWriter(StringWriter().also { stringWriter = it }, true)
        ServerSession(input, output).run()

        val messages = stringWriter.toString().lines().filter { it.isNotBlank() }.map { decode(it) }
        val error = messages.filterIsInstance<ErrorMessage>().firstOrNull()
        assertNotNull(error, "no error emitted for version mismatch")
        assertTrue("protocol version mismatch" in error.message, "unexpected message: ${error.message}")
    }

    @Test
    fun `recorder fires with a full BattleResult when metadata is supplied`() {
        val inputLines = mutableListOf<String>()
        inputLines.add(encode(TeamSet(side = com.pokemon.battle.model.Side.SIDE_1, team = side1Team)))
        inputLines.add(encode(TeamSet(side = com.pokemon.battle.model.Side.SIDE_2, team = side2Team)))
        val flamethrower = Move("Flamethrower", com.pokemon.battle.model.Type.FIRE, com.pokemon.battle.model.MoveCategory.SPECIAL, 90)
        val sludgeBomb = Move("Sludge Bomb", com.pokemon.battle.model.Type.POISON, com.pokemon.battle.model.MoveCategory.SPECIAL, 90)
        val choicesPerTurn =
            encode(
                ChoiceSubmit(
                    choices =
                        TurnChoices(
                            mapOf(
                                Slot.p1() to TurnChoice.UseMove(flamethrower),
                                Slot.p2() to TurnChoice.UseMove(sludgeBomb),
                            ),
                        ).toJson(),
                ),
            )
        repeat(50) { inputLines.add(choicesPerTurn) }

        val input = BufferedReader(StringReader(inputLines.joinToString("\n") + "\n"))
        val output = PrintWriter(StringWriter(), true)

        var recordedResult: com.pokemon.battle.loop.BattleResult? = null
        var recordedMetadata: com.pokemon.battle.persistence.BattleMetadata? = null
        val capturingRecorder =
            com.pokemon.battle.persistence.BattleRecorder { result, metadata ->
                recordedResult = result
                recordedMetadata = metadata
            }
        val metadata =
            com.pokemon.battle.persistence.BattleMetadata(
                battleId = "session-recording-test",
                startedAtEpochMs = 100L,
                endedAtEpochMs = 100L,
                formatTag = "test",
            )

        ServerSession(
            input,
            output,
            maxTurns = 10,
            recorder = capturingRecorder,
            metadata = metadata,
        ).run()

        assertNotNull(recordedResult, "recorder should have been invoked once the battle ended")
        assertNotNull(recordedMetadata, "metadata should have been passed to the recorder")
        assertTrue(recordedResult!!.turnHistory.isNotEmpty(), "captured BattleResult should include turns")
        // endedAtEpochMs gets re-stamped via withEnded() at record time; verify it was updated.
        assertTrue(
            recordedMetadata!!.endedAtEpochMs >= 100L,
            "endedAtEpochMs should be stamped at record time, got ${recordedMetadata!!.endedAtEpochMs}",
        )
    }

    @Test
    fun `no metadata means no recording (default behavior)`() {
        val inputLines = mutableListOf<String>()
        inputLines.add(encode(TeamSet(side = com.pokemon.battle.model.Side.SIDE_1, team = side1Team)))
        inputLines.add(encode(TeamSet(side = com.pokemon.battle.model.Side.SIDE_2, team = side2Team)))
        val flamethrower = Move("Flamethrower", com.pokemon.battle.model.Type.FIRE, com.pokemon.battle.model.MoveCategory.SPECIAL, 90)
        val sludgeBomb = Move("Sludge Bomb", com.pokemon.battle.model.Type.POISON, com.pokemon.battle.model.MoveCategory.SPECIAL, 90)
        val choicesPerTurn =
            encode(
                ChoiceSubmit(
                    choices =
                        TurnChoices(
                            mapOf(
                                Slot.p1() to TurnChoice.UseMove(flamethrower),
                                Slot.p2() to TurnChoice.UseMove(sludgeBomb),
                            ),
                        ).toJson(),
                ),
            )
        repeat(50) { inputLines.add(choicesPerTurn) }
        val input = BufferedReader(StringReader(inputLines.joinToString("\n") + "\n"))
        val output = PrintWriter(StringWriter(), true)

        var invoked = false
        val recorder = com.pokemon.battle.persistence.BattleRecorder { _, _ -> invoked = true }

        // Passing a recorder but no metadata should skip recording — metadata is
        // the signal that "this run is being tracked."
        ServerSession(input, output, maxTurns = 10, recorder = recorder, metadata = null).run()

        assertTrue(!invoked, "recorder must not fire when metadata is null")
    }

    private lateinit var stringWriter: StringWriter

    private fun encode(message: ClientMessage): String = json.encodeToString(ClientMessage.serializer(), message)

    private fun decode(line: String): ServerMessage = json.decodeFromString(ServerMessage.serializer(), line)
}
