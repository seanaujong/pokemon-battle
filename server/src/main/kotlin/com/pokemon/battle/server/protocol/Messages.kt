package com.pokemon.battle.server.protocol

import com.pokemon.battle.engine.serialization.BattleEventJson
import com.pokemon.battle.engine.serialization.InputRequestJson
import com.pokemon.battle.engine.serialization.InputResponseJson
import com.pokemon.battle.engine.serialization.TurnChoicesJson
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Slot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire protocol (v1) between the JVM server and any out-of-JVM client. Line-delimited
 * JSON over stdin/stdout. One JSON object per line, no pretty printing.
 *
 * See diary 069 for design.
 *
 * **[PROTOCOL_VERSION] is a mismatch-detector, not a backwards-compatibility promise.**
 * Server and every checked-in client (smoke test, `:cli`) live in this repo and move
 * together. If this project ever grows deployed clients we can't update in lockstep,
 * that's the point to revisit — add migration logic, deprecation windows, the usual
 * BC machinery. Until then, bumping to v2 is a single atomic commit across both
 * sides and no old v1 clients exist to break.
 */
const val PROTOCOL_VERSION: Int = 1

// --- Client → server ---

@Serializable
sealed interface ClientMessage {
    val protocolVersion: Int
}

@Serializable
@SerialName("team_set")
data class TeamSet(
    val side: Side,
    val team: String,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ClientMessage

@Serializable
@SerialName("choice")
data class ChoiceSubmit(
    val choices: TurnChoicesJson,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ClientMessage

@Serializable
@SerialName("input_response")
data class InputResponseSubmit(
    val response: InputResponseJson,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ClientMessage

@Serializable
@SerialName("faint_replacement")
data class FaintReplacement(
    val slot: Slot,
    val benchIndex: Int,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ClientMessage

// --- Server → client ---

@Serializable
sealed interface ServerMessage {
    val protocolVersion: Int
}

/**
 * Active-slot summary sent in [Ready]. [moves] carries the full [Move] objects — not
 * just names — so an out-of-JVM client can echo them back in [ChoiceSubmit] without
 * needing to reconstruct the shape from a catalog.
 */
@Serializable
data class SlotSummary(
    val slot: Slot,
    val species: String,
    val maxHp: Int,
    val moves: List<Move>,
)

@Serializable
data class BenchMember(
    val side: Side,
    val index: Int,
    val species: String,
    val maxHp: Int,
    val moves: List<Move>,
)

@Serializable
@SerialName("ready")
data class Ready(
    val slots: List<SlotSummary>,
    val benches: List<BenchMember>,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage

/** Emitted before every turn, telling the client it may submit a choice. */
@Serializable
@SerialName("choice_request")
data class ChoiceRequest(
    val turn: Int,
    val activeSlots: List<Slot>,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage

/** Events emitted by one full turn, including any faint-replacement events at the end. */
@Serializable
@SerialName("turn_events")
data class TurnEvents(
    val turn: Int,
    val events: List<BattleEventJson>,
    val replacementEvents: List<BattleEventJson>,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage

@Serializable
@SerialName("input_request")
data class InputRequestMessage(
    val request: InputRequestJson,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage

@Serializable
@SerialName("faint_replacement_request")
data class FaintReplacementRequest(
    val slot: Slot,
    val eligibleBenchIndices: List<Int>,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage

@Serializable
@SerialName("result")
data class Result(
    val winner: Side?,
    val turns: Int,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage

@Serializable
@SerialName("error")
data class ErrorMessage(
    val message: String,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage
