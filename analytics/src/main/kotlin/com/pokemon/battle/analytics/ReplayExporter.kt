package com.pokemon.battle.analytics

import com.pokemon.battle.engine.serialization.BattleEventJson
import com.pokemon.battle.engine.serialization.toJson
import com.pokemon.battle.loop.BattleResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Serializes a completed [BattleResult]'s event stream to pretty-printed JSON. See
 * diary 042 (analytics consumer) and diary 060 (DTO layer used to decouple on-disk
 * format from engine domain types).
 *
 * The output is a single JSON array of events in chronological order, flattened
 * across turn boundaries (turn-scoping can be reconstructed from the original
 * [com.pokemon.battle.loop.TurnRecord] shape if needed later).
 */
object ReplayExporter {
    private val json =
        Json {
            prettyPrint = true
            classDiscriminator = "type"
        }

    fun toJson(result: BattleResult): String {
        val events: List<BattleEventJson> =
            result.turnHistory
                .flatMap { it.events + it.replacementEvents }
                .map { it.toJson() }
        return json.encodeToString(ListSerializer(BattleEventJson.serializer()), events)
    }
}
