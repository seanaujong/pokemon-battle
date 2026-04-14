package com.pokemon.battle.persistence

import com.pokemon.battle.engine.serialization.BattleEventJson
import com.pokemon.battle.model.Side
import kotlinx.serialization.Serializable

/**
 * On-disk shape of a completed battle. Metadata sits alongside the event stream;
 * the final state is deliberately **not** serialized — it can be reconstructed
 * by replaying events if needed, and the events are the source of truth anyway
 * (diary 042 / the event-sourcing shape). Skipping `finalState` keeps the format
 * small and avoids baking a snapshot of a complex immutable graph into every
 * saved battle.
 *
 * `turns` preserves the per-turn grouping from [com.pokemon.battle.loop.TurnRecord]
 * so replay / analytics consumers can answer "what happened on turn 3" without
 * scanning for boundary markers.
 */
@Serializable
data class PersistedBattle(
    val metadata: BattleMetadata,
    val winner: Side?,
    val turns: List<PersistedTurn>,
)

@Serializable
data class PersistedTurn(
    val turnNumber: Int,
    val events: List<BattleEventJson>,
    val replacementEvents: List<BattleEventJson>,
)
