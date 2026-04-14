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
import kotlin.test.assertTrue

class MultiHitMovesTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val noChance: ChanceCheck = { _, _ -> false }

    /**
     * Build a `roll` lambda where `roll(2..5)` (the multi-hit count sampler)
     * returns [hitCount] deterministically, and every other roll returns 100
     * (max damage roll, no crits — crit is `roll(1..24) == 1`).
     */
    private fun fixedHitCountRoll(hitCount: Int): (IntRange) -> Int = { range -> if (range == 2..5) hitCount else 100 }

    private fun pipeline(roll: (IntRange) -> Int) =
        TurnPipeline(
            listOf(
                MoveOrderPhase(GenVRegistries),
                SwitchPhase(GenVRegistries),
                MoveExecutionPhase(GenVRegistries, roll = roll, chanceCheck = noChance),
                EndOfTurnPhase(GenVRegistries),
            ),
        )

    // ============================================================
    // Mainline Pokemon mechanics — reachable in normal play
    // ============================================================

    @Test
    fun `Rock Blast hits 5 times when roll picks 5`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(blastoise, currentHp = blastoise.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.ROCK_BLAST),
                TurnChoice.UseMove(MoveDex.TACKLE),
            )

        val result = pipeline(fixedHitCountRoll(5)).resolveToCompletion(state, choices)

        val hits = result.events.filterIsInstance<DamageDealt>().filter { it.target == Slot.p2() }
        assertEquals(5, hits.size, "Rock Blast should produce 5 DamageDealt events against the target")
        assertTrue(hits.all { it.amount > 0 }, "Each hit deals damage")
    }

    @Test
    fun `Rock Blast hits 2 times when roll picks 2`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(blastoise, currentHp = blastoise.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.ROCK_BLAST),
                TurnChoice.UseMove(MoveDex.TACKLE),
            )

        val result = pipeline(fixedHitCountRoll(2)).resolveToCompletion(state, choices)

        val hits = result.events.filterIsInstance<DamageDealt>().filter { it.target == Slot.p2() }
        assertEquals(2, hits.size)
    }

    @Test
    fun `Double Slap respects the hit count`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(blastoise, currentHp = blastoise.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.DOUBLE_SLAP),
                TurnChoice.UseMove(MoveDex.TACKLE),
            )

        val result = pipeline(fixedHitCountRoll(4)).resolveToCompletion(state, choices)

        val hits = result.events.filterIsInstance<DamageDealt>().filter { it.target == Slot.p2() }
        assertEquals(4, hits.size, "Double Slap with hitCount=4 should produce 4 DamageDealt events")
    }

    @Test
    fun `Focus Sash survives first multi-hit blow, a later hit KOs after Sash consumed`() {
        // Sash only fires when the HOLDER is at full HP and the incoming hit would KO.
        // So for Sash + multi-hit to interact meaningfully, the FIRST hit of Rock Blast
        // must be lethal. Garchomp at +6 Attack vs frail Pikachu achieves this.
        val garchomp = Pokemon(pokedex["Garchomp"]!!, level = 50)
        val pikachu = Pokemon(pokedex["Pikachu"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(garchomp, currentHp = garchomp.maxHp, statStages = StatStages(attack = 6)),
                PokemonState(pikachu, currentHp = pikachu.maxHp, item = Item.FOCUS_SASH),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.ROCK_BLAST),
                TurnChoice.UseMove(MoveDex.TACKLE),
            )

        val result = pipeline(fixedHitCountRoll(5)).resolveToCompletion(state, choices)

        // Sash should have been consumed exactly once — on the first lethal hit.
        val sashConsumed = result.events.filterIsInstance<ItemConsumed>().filter { it.item == Item.FOCUS_SASH }
        assertEquals(1, sashConsumed.size, "Focus Sash should be consumed exactly once across the multi-hit chain")

        // Pikachu should faint — at 1 HP post-Sash, the very next hit KOs.
        val fainted = result.events.filterIsInstance<PokemonFainted>().filter { it.slot == Slot.p2() }
        assertEquals(1, fainted.size, "Pikachu should faint from the hit after Sash consumed")

        // And multi-hit should have stopped after the KO — not all 5 hits should have landed.
        val hits = result.events.filterIsInstance<DamageDealt>().filter { it.target == Slot.p2() }
        assertTrue(hits.size < 5, "Multi-hit should have stopped after the KO, not run all 5 hits")
    }

    @Test
    fun `Multi-hit stops once target faints`() {
        // Blastoise at 1 HP — first Rock Blast hit KOs, so we should see only 1 DamageDealt.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(blastoise, currentHp = 1),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.ROCK_BLAST),
                TurnChoice.UseMove(MoveDex.TACKLE),
            )

        val result = pipeline(fixedHitCountRoll(5)).resolveToCompletion(state, choices)

        val hits = result.events.filterIsInstance<DamageDealt>().filter { it.target == Slot.p2() }
        assertEquals(1, hits.size, "Multi-hit should stop once the target faints")
        assertEquals(1, result.events.filterIsInstance<PokemonFainted>().count { it.slot == Slot.p2() })
    }
}
