package com.pokemon.battle.analytics

import com.pokemon.battle.engine.serialization.CriticalHitJson
import com.pokemon.battle.engine.serialization.MoveAttemptedJson
import com.pokemon.battle.engine.serialization.PokemonFaintedJson
import com.pokemon.battle.model.Side
import com.pokemon.battle.persistence.PersistedBattle

/**
 * A single head-to-head matchup result. Used by [BattleCorpus.matchupWinRates]
 * to summarize "player X vs player Y" outcomes. Draws are tracked separately
 * because they're a third-category outcome, not a partial-credit win.
 */
data class MatchupResult(
    val battles: Int,
    val sideAWins: Int,
    val sideBWins: Int,
    val draws: Int,
) {
    val sideAWinRate: Double get() = if (battles == 0) 0.0 else sideAWins.toDouble() / battles
    val sideBWinRate: Double get() = if (battles == 0) 0.0 else sideBWins.toDouble() / battles
}

/**
 * Aggregations over a corpus of [PersistedBattle]. Pure sequence folds — no I/O,
 * no state, one pass per aggregation. `:persistence` supplies the corpus (from
 * disk, network, test fixtures, wherever); this file answers analytical
 * questions over it.
 *
 * See diary 078 for the layering rationale — `:analytics` stays I/O-free; the
 * only reason it depends on `:persistence` is for the [PersistedBattle] type.
 *
 * Each function takes a [Sequence] rather than a [List] so large corpora
 * stream rather than load into memory. Callers that already have a list can
 * `.asSequence()` cheaply.
 */
object BattleCorpus {
    /**
     * Distribution of outcomes across the corpus. Keys include `null` for
     * draws / turn-limit results. Values sum to [total] entries in the input
     * unless the sequence yields duplicates (it shouldn't).
     */
    fun winsBySide(battles: Sequence<PersistedBattle>): Map<Side?, Int> = battles.groupingBy { it.winner }.eachCount()

    /**
     * Fraction of battles each side won. Useful headline number; ignores
     * draws / turn-limit for the percentages (but they're preserved in the
     * denominator when [includeDraws] is true — the default, so rates are
     * honest).
     */
    fun winRate(battles: Sequence<PersistedBattle>): Map<Side?, Double> {
        val counts = winsBySide(battles)
        val total = counts.values.sum().coerceAtLeast(1)
        return counts.mapValues { it.value.toDouble() / total }
    }

    /**
     * How often each move was attempted across the corpus, keyed by move
     * name. Move objects aren't directly comparable-by-identity across JSON
     * round-trips, so name is the stable key.
     */
    fun moveUsage(battles: Sequence<PersistedBattle>): Map<String, Int> =
        battles
            .flatMap { it.allEvents() }
            .filterIsInstance<MoveAttemptedJson>()
            .groupingBy { it.move.name }
            .eachCount()

    /**
     * Total number of critical hits observed. Uses the [CriticalHitJson]
     * event (diary 076 made crits a first-class event), not the legacy
     * boolean on damage events.
     */
    fun criticalHitCount(battles: Sequence<PersistedBattle>): Int =
        battles
            .flatMap { it.allEvents() }
            .filterIsInstance<CriticalHitJson>()
            .count()

    /**
     * Total KOs observed. Independent of winner — a battle that ends in a
     * double-KO still contributes two.
     */
    fun koCount(battles: Sequence<PersistedBattle>): Int =
        battles
            .flatMap { it.allEvents() }
            .filterIsInstance<PokemonFaintedJson>()
            .count()

    /**
     * Group the corpus by `(side1PlayerTag, side2PlayerTag)` pairs and report
     * win / loss / draw counts per matchup. Battles with no `playerTags` are
     * bucketed under `(?, ?)` so they don't silently vanish.
     *
     * The result distinguishes orientation — "TypeAI (side 1) vs RandomAI
     * (side 2)" is a different key from "RandomAI (side 1) vs TypeAI (side 2)".
     * Many AI matchups aren't symmetric (move-order ties, first-turn access
     * to weather, etc.), and collapsing them would hide that.
     */
    fun matchupWinRates(battles: Sequence<PersistedBattle>): Map<Pair<String, String>, MatchupResult> {
        val buckets = mutableMapOf<Pair<String, String>, MutableMatchupCounts>()
        for (battle in battles) {
            val side1Tag = battle.metadata.playerTags[Side.SIDE_1.name] ?: "?"
            val side2Tag = battle.metadata.playerTags[Side.SIDE_2.name] ?: "?"
            val counts = buckets.getOrPut(side1Tag to side2Tag) { MutableMatchupCounts() }
            counts.battles++
            when (battle.winner) {
                Side.SIDE_1 -> counts.sideAWins++
                Side.SIDE_2 -> counts.sideBWins++
                null -> counts.draws++
            }
        }
        return buckets.mapValues { (_, c) -> MatchupResult(c.battles, c.sideAWins, c.sideBWins, c.draws) }
    }

    private class MutableMatchupCounts(
        var battles: Int = 0,
        var sideAWins: Int = 0,
        var sideBWins: Int = 0,
        var draws: Int = 0,
    )

    private fun PersistedBattle.allEvents() = turns.asSequence().flatMap { (it.events + it.replacementEvents).asSequence() }
}
