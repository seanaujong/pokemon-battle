package com.pokemon.battle.analytics

import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.ItemConsumed
import com.pokemon.battle.engine.ItemDamage
import com.pokemon.battle.engine.ItemHealing
import com.pokemon.battle.engine.MoveAttempted
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.loop.BattleResult
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Slot

/**
 * Single-battle summary computed by [BattleAnalyzer]. See diary 042.
 *
 * All maps are non-null; missing keys mean "zero occurrences." Fields intentionally
 * project a readable shape — the raw event stream on [BattleResult] remains the source
 * of truth for anything this summary doesn't capture.
 */
data class BattleSummary(
    val winner: Side?,
    val turnsPlayed: Int,
    val koCount: Map<Slot, Int>,
    val movesUsed: Map<String, Int>,
    val itemsTriggered: Map<Item, Int>,
    val abilitiesTriggered: Map<Ability, Int>,
    val criticalHits: Int,
    val damageDealt: Map<Slot, Int>,
)

/**
 * Fold a completed [BattleResult] into a [BattleSummary]. Pure function; a second
 * consumer of the event stream, parallel to the text renderer (diary 042).
 */
object BattleAnalyzer {
    fun analyze(result: BattleResult): BattleSummary {
        val allEvents: List<BattleEvent> =
            result.turnHistory.flatMap { it.events + it.replacementEvents }
        val gameEvents = allEvents.filterIsInstance<GameEvent>()

        val movesUsed =
            gameEvents.filterIsInstance<MoveAttempted>()
                .groupingBy { it.move.name }
                .eachCount()

        val abilitiesTriggered =
            gameEvents.filterIsInstance<AbilityTriggered>()
                .groupingBy { it.ability }
                .eachCount()

        val itemEvents =
            gameEvents.asSequence()
                .mapNotNull { event ->
                    when (event) {
                        is ItemConsumed -> event.item
                        is ItemHealing -> event.item
                        is ItemDamage -> event.item
                        else -> null
                    }
                }
                .toList()
        val itemsTriggered = itemEvents.groupingBy { it }.eachCount()

        val criticalHits =
            gameEvents.filterIsInstance<DamageDealt>().count { it.critical }

        val damageDealt =
            gameEvents.filterIsInstance<DamageDealt>()
                .groupBy { it.target }
                .mapValues { (_, damages) -> damages.sumOf { it.amount } }

        val koCount =
            gameEvents.filterIsInstance<PokemonFainted>()
                .groupingBy { it.slot }
                .eachCount()

        return BattleSummary(
            winner = result.winner,
            turnsPlayed = result.turnHistory.size,
            koCount = koCount,
            movesUsed = movesUsed,
            itemsTriggered = itemsTriggered,
            abilitiesTriggered = abilitiesTriggered,
            criticalHits = criticalHits,
            damageDealt = damageDealt,
        )
    }
}
