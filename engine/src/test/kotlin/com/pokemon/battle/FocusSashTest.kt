package com.pokemon.battle

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.ItemConsumed
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatStages
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusSashTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val noChance: ChanceCheck = { _, _ -> false }
    private val fixedRoll: (IntRange) -> Int = { 100 }

    private fun pipeline() =
        TurnPipeline(
            listOf(
                MoveOrderPhase(GenVRegistries),
                SwitchPhase(GenVRegistries),
                MoveExecutionPhase(GenVRegistries, roll = fixedRoll, chanceCheck = noChance),
                EndOfTurnPhase(GenVRegistries),
            ),
        )

    // ============================================================
    // Mainline Pokemon mechanics — reachable in normal play
    // ============================================================

    @Test
    fun `Focus Sash survives a KO hit at full HP`() {
        // Charizard at +2 SpA Flamethrower would overkill Venusaur. Sash saves it at 1 HP.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp, statStages = StatStages(specialAttack = 2)),
                PokemonState(venusaur, currentHp = venusaur.maxHp, item = Item.FOCUS_SASH),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val damage = result.events.filterIsInstance<DamageDealt>().first { it.target == Slot.p2() }
        val finalState = result.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }
        val venusaurAfter = finalState.pokemonFor(Slot.p2())

        // Damage should leave Venusaur at exactly 1 HP
        assertEquals(venusaur.maxHp - 1, damage.amount, "Focus Sash caps damage at maxHp - 1")
        assertEquals(1, venusaurAfter.currentHp, "Venusaur should be at 1 HP")
        assertFalse(venusaurAfter.isFainted, "Venusaur should not be fainted")

        // Item should be consumed
        val consumed = result.events.filterIsInstance<ItemConsumed>()
        assertEquals(1, consumed.size)
        assertEquals(Item.FOCUS_SASH, consumed[0].item)
        assertEquals(null, venusaurAfter.item, "Focus Sash should be gone")

        // No faint event
        assertTrue(result.events.filterIsInstance<PokemonFainted>().none { it.slot == Slot.p2() })
    }

    @Test
    fun `Focus Sash does not trigger when not at full HP`() {
        // Venusaur at less than full HP with Sash — even with +2 SpA Charizard, Sash shouldn't trigger
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp, statStages = StatStages(specialAttack = 2)),
                PokemonState(venusaur, currentHp = venusaur.maxHp - 10, item = Item.FOCUS_SASH),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val finalState = result.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }

        // Venusaur should be KO'd — Sash doesn't trigger
        assertTrue(
            result.events.filterIsInstance<PokemonFainted>().any { it.slot == Slot.p2() },
            "Venusaur should faint without Sash save",
        )
        assertTrue(result.events.filterIsInstance<ItemConsumed>().isEmpty(), "Sash should not be consumed")
        assertEquals(Item.FOCUS_SASH, finalState.pokemonFor(Slot.p2()).item, "Sash remains held")
    }

    @Test
    fun `Focus Sash does not trigger on non-lethal hits`() {
        // Charizard hits Blastoise with Flamethrower — Fire vs Water is 0.5x, not lethal at 50.
        // Sash shouldn't trigger; should still be held.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(blastoise, currentHp = blastoise.maxHp, item = Item.FOCUS_SASH),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val finalState = result.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }

        // Blastoise takes some damage but isn't KO'd — Sash should NOT trigger
        val damage = result.events.filterIsInstance<DamageDealt>().first { it.target == Slot.p2() }
        assertTrue(damage.amount > 0, "Blastoise takes damage")
        assertTrue(damage.amount < blastoise.maxHp, "But less than maxHp — not lethal")
        assertTrue(result.events.filterIsInstance<ItemConsumed>().isEmpty(), "Sash not triggered by non-lethal hit")
        assertEquals(Item.FOCUS_SASH, finalState.pokemonFor(Slot.p2()).item, "Sash remains held")
    }

    @Test
    fun `second hit after Sash use KOs`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp, statStages = StatStages(specialAttack = 2)),
                PokemonState(venusaur, currentHp = venusaur.maxHp, item = Item.FOCUS_SASH),
            )

        // Turn 1: +2 SpA Flamethrower hits Sash, Venusaur at 1 HP
        val turn1Choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )
        val turn1 = pipeline().resolveToCompletion(state, turn1Choices)
        val afterTurn1 = turn1.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }
        assertEquals(1, afterTurn1.pokemonFor(Slot.p2()).currentHp)
        assertEquals(null, afterTurn1.pokemonFor(Slot.p2()).item)

        // Turn 2: Any hit KOs — Sash is gone
        val turn2Choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )
        val turn2 = pipeline().resolveToCompletion(afterTurn1, turn2Choices)
        assertTrue(
            turn2.events.filterIsInstance<PokemonFainted>().any { it.slot == Slot.p2() },
            "Venusaur should faint on turn 2 with no Sash",
        )
    }
}
