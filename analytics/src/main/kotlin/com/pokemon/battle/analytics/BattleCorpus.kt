package com.pokemon.battle.analytics

import com.pokemon.battle.engine.serialization.CriticalHitJson
import com.pokemon.battle.engine.serialization.MoveAttemptedJson
import com.pokemon.battle.engine.serialization.PokemonFaintedJson
import com.pokemon.battle.model.Side
import com.pokemon.battle.persistence.PersistedBattle

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

    private fun PersistedBattle.allEvents() = turns.asSequence().flatMap { (it.events + it.replacementEvents).asSequence() }
}
