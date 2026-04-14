package com.pokemon.battle.server

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.SwitchIn
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.TurnResolution
import com.pokemon.battle.engine.resolveSwitchInAbility
import com.pokemon.battle.engine.serialization.toJson
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Species
import com.pokemon.battle.persistence.withEnded
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import com.pokemon.battle.server.protocol.BenchMember
import com.pokemon.battle.server.protocol.ChoiceRequest
import com.pokemon.battle.server.protocol.ChoiceSubmit
import com.pokemon.battle.server.protocol.ClientMessage
import com.pokemon.battle.server.protocol.ErrorMessage
import com.pokemon.battle.server.protocol.FaintReplacement
import com.pokemon.battle.server.protocol.FaintReplacementRequest
import com.pokemon.battle.server.protocol.InputRequestMessage
import com.pokemon.battle.server.protocol.InputResponseSubmit
import com.pokemon.battle.server.protocol.Ready
import com.pokemon.battle.server.protocol.Result
import com.pokemon.battle.server.protocol.ServerMessage
import com.pokemon.battle.server.protocol.SlotSummary
import com.pokemon.battle.server.protocol.TeamSet
import com.pokemon.battle.server.protocol.TurnEvents
import com.pokemon.battle.server.team.ResolvedPokemon
import com.pokemon.battle.server.team.SmogonParser
import com.pokemon.battle.server.team.TeamBuilder
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.PrintWriter

/**
 * JSONL-over-streams battle session. Each turn:
 *   1. Server emits [ChoiceRequest].
 *   2. Client responds with [ChoiceSubmit].
 *   3. Server may emit [InputRequestMessage] (e.g. U-turn's switch target); client
 *      responds with [InputResponseSubmit].
 *   4. Server handles faint replacements via [FaintReplacementRequest] /
 *      [FaintReplacement] exchanges.
 *   5. Server emits [TurnEvents] with the complete event list.
 *   6. On win/draw/turn-limit the server emits [Result] and stops.
 *
 * The session owns its own loop rather than reusing [com.pokemon.battle.loop.BattleLoop]:
 * we need to interleave protocol messages into the turn, which would require reshaping
 * BattleLoop's callbacks. The logic mirrors BattleLoop.run() closely; diverging
 * behavior would be a bug.
 *
 * Battle orchestration decomposes into stepped helpers (team read, ready message,
 * replacement loop, etc.) — hence the `TooManyFunctions` suppression.
 */
@Suppress("TooManyFunctions")
class ServerSession(
    private val input: BufferedReader,
    private val output: PrintWriter,
    private val pokedex: Map<String, Species> = Pokedex.loadFromClasspath(),
    private val maxTurns: Int = 100,
    // Optional recorder — default no-op keeps callers that don't care from needing
    // a :persistence dep. Wired by ServerMain based on env var.
    private val recorder: com.pokemon.battle.persistence.BattleRecorder =
        com.pokemon.battle.persistence.BattleRecorder.NoOp,
    private val metadata: com.pokemon.battle.persistence.BattleMetadata? = null,
) {
    private val json = Json { classDiscriminator = "type" }
    private val movePools = mutableMapOf<Slot, List<Move>>()
    private val turnHistory = mutableListOf<com.pokemon.battle.loop.TurnRecord>()

    fun run() {
        try {
            runInner()
        } catch (ex: IllegalStateException) {
            emit(ErrorMessage(ex.message ?: "session aborted"))
        } catch (ex: IllegalArgumentException) {
            emit(ErrorMessage(ex.message ?: "session aborted"))
        } catch (ex: java.io.IOException) {
            // Recorder failures (disk full, permissions) or stream failures — emit an
            // error and close cleanly rather than let the server crash mid-session.
            // Finding from diary 082's retroactive review.
            emit(ErrorMessage("I/O error: ${ex.message ?: "unknown"}"))
        }
    }

    @Suppress("CyclomaticComplexMethod") // Turn loop + pause loop + replacement handling
    private fun runInner() {
        val (side1, side2) = readTeams()
        var state = initialState(side1, side2)
        populateMovePools(side1, side2)
        emit(readyMessage(state, side1, side2))

        val pipeline =
            TurnPipeline(
                listOf(
                    MoveOrderPhase(GenVRegistries),
                    SwitchPhase(GenVRegistries),
                    MoveExecutionPhase(GenVRegistries),
                    EndOfTurnPhase(GenVRegistries),
                ),
            )

        var turnNumber = 1
        while (turnNumber <= maxTurns) {
            emit(ChoiceRequest(turn = turnNumber, activeSlots = state.allSlots().toList()))
            val choices = readClient<ChoiceSubmit>().choices.toDomain()

            var resolution: TurnResolution = pipeline.resolve(state, choices)
            while (resolution is TurnResolution.NeedInput) {
                val request = resolution.state.pendingInput ?: error("NeedInput without pendingInput")
                emit(InputRequestMessage(request.toJson()))
                val response = readClient<InputResponseSubmit>().response.toDomain()
                resolution = pipeline.resume(resolution.state, choices, response)
            }
            val completed = resolution as TurnResolution.Completed
            state = completed.state.copy(turn = completed.state.turn + 1)

            val replacementEvents =
                handleFaintReplacements(state).also { (_, _) -> }
            state = replacementEvents.first

            turnHistory.add(
                com.pokemon.battle.loop.TurnRecord(
                    turnNumber = turnNumber,
                    events = completed.events,
                    replacementEvents = replacementEvents.second,
                ),
            )
            emit(
                TurnEvents(
                    turn = turnNumber,
                    events = completed.events.map { it.toJson() },
                    replacementEvents = replacementEvents.second.map { it.toJson() },
                ),
            )

            val winner = checkWinner(state)
            if (winner != null || state.isDefeated(Side.SIDE_1) && state.isDefeated(Side.SIDE_2)) {
                emit(Result(winner = winner, turns = turnNumber))
                recordBattle(state, winner)
                return
            }
            turnNumber++
        }
        emit(Result(winner = null, turns = maxTurns))
        recordBattle(state, winner = null)
    }

    private fun recordBattle(
        finalState: BattleState,
        winner: Side?,
    ) {
        val meta = metadata ?: return
        val result =
            com.pokemon.battle.loop.BattleResult(
                winner = winner,
                finalState = finalState,
                turnHistory = turnHistory.toList(),
            )
        recorder.record(result, meta.withEnded())
    }

    private fun readTeams(): Pair<List<ResolvedPokemon>, List<ResolvedPokemon>> {
        val first = readClient<TeamSet>()
        val second = readClient<TeamSet>()
        val bySide = mapOf(first.side to first.team, second.side to second.team)
        require(Side.SIDE_1 in bySide && Side.SIDE_2 in bySide) {
            "both sides must be supplied"
        }
        return TeamBuilder.build(SmogonParser.parseTeam(bySide.getValue(Side.SIDE_1)), pokedex) to
            TeamBuilder.build(SmogonParser.parseTeam(bySide.getValue(Side.SIDE_2)), pokedex)
    }

    private fun initialState(
        side1: List<ResolvedPokemon>,
        side2: List<ResolvedPokemon>,
    ): BattleState {
        require(side1.isNotEmpty()) { "SIDE_1 team is empty" }
        require(side2.isNotEmpty()) { "SIDE_2 team is empty" }
        return BattleState.singles(
            p1 = side1.first().state,
            p2 = side2.first().state,
            p1Bench = side1.drop(1).map { it.state },
            p2Bench = side2.drop(1).map { it.state },
        )
    }

    private fun populateMovePools(
        side1: List<ResolvedPokemon>,
        side2: List<ResolvedPokemon>,
    ) {
        movePools[Slot.p1()] = side1.first().moves
        movePools[Slot.p2()] = side2.first().moves
        // Bench move pools are tracked via their BenchMember entries in the Ready message;
        // when a switch happens, the client looks up the new active's moves from there.
    }

    private fun readyMessage(
        state: BattleState,
        side1: List<ResolvedPokemon>,
        side2: List<ResolvedPokemon>,
    ): Ready {
        val slots =
            state.allSlots().map { slot ->
                val ps = state.pokemonFor(slot)
                SlotSummary(
                    slot = slot,
                    species = ps.pokemon.species.name,
                    maxHp = ps.maxHp,
                    moves = movePools[slot] ?: emptyList(),
                )
            }
        val benches =
            side1.drop(1).mapIndexed { i, p -> benchMember(Side.SIDE_1, i, p) } +
                side2.drop(1).mapIndexed { i, p -> benchMember(Side.SIDE_2, i, p) }
        return Ready(slots = slots, benches = benches)
    }

    private fun benchMember(
        side: Side,
        index: Int,
        member: ResolvedPokemon,
    ): BenchMember =
        BenchMember(
            side = side,
            index = index,
            species = member.state.pokemon.species.name,
            maxHp = member.state.maxHp,
            moves = member.moves,
        )

    private fun handleFaintReplacements(initialState: BattleState): Pair<BattleState, List<com.pokemon.battle.engine.BattleEvent>> {
        var current = initialState
        val events = mutableListOf<com.pokemon.battle.engine.BattleEvent>()
        for (slot in current.allSlots()) {
            val ps = current.pokemonFor(slot)
            if (!ps.isFainted) continue
            val bench = current.benchFor(slot.side)
            val eligible = bench.withIndex().filter { !it.value.isFainted }.map { it.index }
            if (eligible.isEmpty()) continue

            emit(FaintReplacementRequest(slot = slot, eligibleBenchIndices = eligible))
            val chosen = readClient<FaintReplacement>().benchIndex
            val switchIn = SwitchIn(slot, chosen)
            events.add(switchIn)
            current = switchIn.apply(current)
            for (abilityEvent in resolveSwitchInAbility(current, slot, GenVRegistries.abilities)) {
                events.add(abilityEvent)
                current = abilityEvent.apply(current)
            }
        }
        return current to events
    }

    private fun checkWinner(state: BattleState): Side? =
        when {
            state.isDefeated(Side.SIDE_1) && state.isDefeated(Side.SIDE_2) -> null
            state.isDefeated(Side.SIDE_1) -> Side.SIDE_2
            state.isDefeated(Side.SIDE_2) -> Side.SIDE_1
            else -> null
        }

    private fun emit(message: ServerMessage) {
        output.println(json.encodeToString(ServerMessage.serializer(), message))
        output.flush()
    }

    private inline fun <reified T : ClientMessage> readClient(): T {
        val line = input.readLine() ?: error("client closed stream while expecting ${T::class.simpleName}")
        val parsed = json.decodeFromString(ClientMessage.serializer(), line)
        check(parsed.protocolVersion == com.pokemon.battle.server.protocol.PROTOCOL_VERSION) {
            "protocol version mismatch: server=${com.pokemon.battle.server.protocol.PROTOCOL_VERSION} " +
                "client=${parsed.protocolVersion}"
        }
        return parsed as? T ?: error("expected ${T::class.simpleName}, got ${parsed::class.simpleName}")
    }
}
