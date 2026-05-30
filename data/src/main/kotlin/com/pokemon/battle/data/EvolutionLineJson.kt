package com.pokemon.battle.data

import kotlinx.serialization.Serializable

/**
 * On-disk JSON shape for an evolution line, decoupled from the domain [EvolutionLine]
 * (the [SpeciesJson] pattern). Trigger/method are stored as raw PokeAPI slugs so the
 * committed bundle stays a faithful projection; [toDomain] maps them to enums.
 */
@Serializable
data class EvolutionLineJson(
    val base: String,
    val edges: List<EvolutionEdgeJson>,
    val learnsets: Map<String, Map<String, List<MoveAcquisitionJson>>>,
) {
    fun toDomain(): EvolutionLine =
        EvolutionLine(
            base = base,
            edges = edges.map { it.toDomain() },
            learnsets =
                learnsets.mapValues { (_, byVg) ->
                    byVg.mapValues { (_, moves) -> moves.map { it.toDomain() } }
                },
        )
}

@Serializable
data class EvolutionEdgeJson(
    val from: String,
    val to: String,
    val trigger: String,
    val minLevel: Int? = null,
    val item: String? = null,
    val minHappiness: Int? = null,
    val heldItem: String? = null,
    val timeOfDay: String? = null,
) {
    fun toDomain(): EvolutionEdge =
        EvolutionEdge(
            from = from,
            to = to,
            trigger = EvolutionTrigger.fromSlug(trigger),
            minLevel = minLevel,
            item = item,
            minHappiness = minHappiness,
            heldItem = heldItem,
            timeOfDay = timeOfDay,
        )
}

@Serializable
data class MoveAcquisitionJson(
    val move: String,
    val method: String,
    val level: Int,
) {
    fun toDomain(): MoveAcquisition =
        MoveAcquisition(
            move = move,
            method = LearnMethod.fromSlug(method),
            level = level,
        )
}
