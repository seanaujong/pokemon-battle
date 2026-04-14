package com.pokemon.battle.server.protocol

import com.pokemon.battle.engine.serialization.BattleEventJson
import com.pokemon.battle.engine.serialization.InputRequestJson
import com.pokemon.battle.engine.serialization.InputResponseJson
import com.pokemon.battle.engine.serialization.TurnChoicesJson
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Slot
import kotlinx.serialization.Serializable

/**
 * Wire protocol (v1) between the JVM server and any out-of-JVM client. Line-delimited
 * JSON over stdin/stdout. One JSON object per line, no pretty printing.
 *
 * See diary 069 for design.
 */
const val PROTOCOL_VERSION: Int = 1

// --- Client → server ---

@Serializable
sealed interface ClientMessage {
    val protocolVersion: Int
}

@Serializable
data class TeamSet(
    val side: Side,
    val team: String,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ClientMessage

@Serializable
data class ChoiceSubmit(
    val choices: TurnChoicesJson,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ClientMessage

@Serializable
data class InputResponseSubmit(
    val response: InputResponseJson,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ClientMessage

@Serializable
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

@Serializable
data class SlotSummary(
    val slot: Slot,
    val species: String,
    val maxHp: Int,
    val moves: List<String>,
)

@Serializable
data class BenchMember(
    val side: Side,
    val index: Int,
    val species: String,
    val maxHp: Int,
    val moves: List<String>,
)

@Serializable
data class Ready(
    val slots: List<SlotSummary>,
    val benches: List<BenchMember>,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage

/** Emitted before every turn, telling the client it may submit a choice. */
@Serializable
data class ChoiceRequest(
    val turn: Int,
    val activeSlots: List<Slot>,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage

/** Events emitted by one full turn, including any faint-replacement events at the end. */
@Serializable
data class TurnEvents(
    val turn: Int,
    val events: List<BattleEventJson>,
    val replacementEvents: List<BattleEventJson>,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage

@Serializable
data class InputRequestMessage(
    val request: InputRequestJson,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage

@Serializable
data class FaintReplacementRequest(
    val slot: Slot,
    val eligibleBenchIndices: List<Int>,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage

@Serializable
data class Result(
    val winner: Side?,
    val turns: Int,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage

@Serializable
data class ErrorMessage(
    val message: String,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage
