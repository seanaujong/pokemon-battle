package com.pokemon.battle.ingest.transform

import com.pokemon.battle.data.EvolutionEdgeJson
import com.pokemon.battle.data.EvolutionLineJson
import com.pokemon.battle.data.GenerationMap
import com.pokemon.battle.data.MoveAcquisitionJson
import com.pokemon.battle.ingest.fetch.PokeApiChainLink
import com.pokemon.battle.ingest.fetch.PokeApiEvolutionChain
import com.pokemon.battle.ingest.fetch.PokeApiPokemonLearnset
import kotlinx.serialization.json.Json

/**
 * Builds an [EvolutionLineJson] bundle from a raw `/evolution-chain` response plus the
 * raw `/pokemon` learnset response for each stage. Pure and network-free: the
 * entrypoint does the fetching, this does the shaping. Output is fully sorted so
 * committed bundles are deterministic (stable diffs, stable golden tests).
 */
object EvolutionLineTransform {
    private val json = Json { ignoreUnknownKeys = true }

    /** Methods the advisor models; "other" slugs (form-change, etc.) are dropped. */
    private val MODELLED_METHODS = setOf("level-up", "machine", "tutor", "egg")
    private const val LEVEL_UP = "level-up"

    fun transform(
        base: String,
        chainRawJson: String,
        learnsetRawJsonBySpecies: Map<String, String>,
    ): EvolutionLineJson {
        val chain = json.decodeFromString(PokeApiEvolutionChain.serializer(), chainRawJson)
        val edges = edgesOf(chain.chain).sortedWith(compareBy({ it.from }, { it.to }))

        val parsed = learnsetRawJsonBySpecies.toSortedMap().mapValues { (_, raw) -> learnsetOf(raw) }
        return EvolutionLineJson(base = base, edges = edges, learnsets = trimToAdvisorRelevant(parsed))
    }

    /**
     * Drops acquisitions the delay advisor can never read, line-wide. A move is only
     * flagged if some stage learns it by level-up, and a flag's `alternativeAccess` is
     * only computed for an already-flagged move — so non-level-up acquisitions of a move
     * that *no* stage level-learns are dead weight (mostly TMs). Cuts bundle size ~2.4x.
     *
     * This trim encodes a downstream assumption ("the advisor only reads non-level-up
     * methods for level-up moves") at build time. It is lossless *only while that holds*;
     * `EvolutionLineTransformTrimTest` is the falsifiable guard that fails if the advisor
     * ever starts reading what this drops.
     */
    internal fun trimToAdvisorRelevant(
        learnsets: Map<String, Map<String, List<MoveAcquisitionJson>>>,
    ): Map<String, Map<String, List<MoveAcquisitionJson>>> {
        val levelUpMoves =
            learnsets.values
                .flatMap { it.values.flatten() }
                .filter { it.method == LEVEL_UP }
                .map { it.move }
                .toSet()
        return learnsets.mapValues { (_, byVersionGroup) ->
            byVersionGroup.mapValues { (_, acquisitions) ->
                acquisitions.filter { it.method == LEVEL_UP || it.move in levelUpMoves }
            }
        }
    }

    private fun edgesOf(root: PokeApiChainLink): List<EvolutionEdgeJson> {
        val edges = mutableListOf<EvolutionEdgeJson>()

        fun walk(node: PokeApiChainLink) {
            for (child in node.evolvesTo) {
                val detail = child.evolutionDetails.firstOrNull()
                edges +=
                    EvolutionEdgeJson(
                        from = node.species.name,
                        to = child.species.name,
                        trigger = detail?.trigger?.name ?: "other",
                        minLevel = detail?.minLevel,
                        item = detail?.item?.name,
                        minHappiness = detail?.minHappiness,
                        heldItem = detail?.heldItem?.name,
                        timeOfDay = detail?.timeOfDay?.ifBlank { null },
                    )
                walk(child)
            }
        }
        walk(root)
        return edges
    }

    /** versionGroup -> sorted, de-duplicated acquisitions. */
    private fun learnsetOf(rawJson: String): Map<String, List<MoveAcquisitionJson>> {
        val pokemon = json.decodeFromString(PokeApiPokemonLearnset.serializer(), rawJson)
        val byVersionGroup = mutableMapOf<String, MutableList<MoveAcquisitionJson>>()
        for (move in pokemon.moves) {
            for (detail in move.versionGroupDetails) {
                if (detail.moveLearnMethod.name !in MODELLED_METHODS) continue
                // Skip version groups we can't attribute to a generation (Japanese-only
                // gen-1 dupes); the CLI groups output by generation, so unmappable
                // version groups would be unattributable noise.
                if (GenerationMap.generationOf(detail.versionGroup.name) == null) continue
                byVersionGroup
                    .getOrPut(detail.versionGroup.name) { mutableListOf() }
                    .add(
                        MoveAcquisitionJson(
                            move = move.move.name,
                            method = detail.moveLearnMethod.name,
                            level = detail.levelLearnedAt,
                        ),
                    )
            }
        }
        return byVersionGroup
            .toSortedMap()
            .mapValues { (_, acquisitions) ->
                acquisitions.distinct().sortedWith(compareBy({ it.move }, { it.method }, { it.level }))
            }
    }
}
