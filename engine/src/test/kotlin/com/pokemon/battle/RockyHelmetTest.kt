package com.pokemon.battle

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.ItemDamage
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RockyHelmetTest {
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
    fun `Rocky Helmet deals 1 over 6 of attacker's max HP as recoil on contact move`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp, item = Item.ROCKY_HELMET)
        val state = BattleState.singles(attacker, defender)

        // Tackle is a contact move — Rocky Helmet fires.
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.TACKLE),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val helmetEvents = result.events.filterIsInstance<ItemDamage>().filter { it.item == Item.ROCKY_HELMET }
        assertEquals(1, helmetEvents.size, "Rocky Helmet should fire once when its holder is hit by a contact move")
        assertEquals(attacker.maxHp / 6, helmetEvents.single().amount, "recoil should be 1/6 max HP")
    }

    @Test
    fun `Rocky Helmet does not fire on non-contact move (Flamethrower)`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp, item = Item.ROCKY_HELMET)
        val state = BattleState.singles(attacker, defender)

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val helmetEvents = result.events.filterIsInstance<ItemDamage>().filter { it.item == Item.ROCKY_HELMET }
        assertEquals(0, helmetEvents.size, "Rocky Helmet should not fire on a non-contact move")
    }

    @Test
    fun `Rocky Helmet does not fire if holder is not damaged`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val gengar = Pokemon(pokedex["Gengar"]!!, level = 50)

        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        // Gengar (Ghost) is immune to Normal moves — Tackle does no damage.
        val defender = PokemonState(gengar, currentHp = gengar.maxHp, item = Item.ROCKY_HELMET)
        val state = BattleState.singles(attacker, defender)

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.TACKLE),
                TurnChoice.UseMove(MoveDex.SHADOW_BALL),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val helmetEvents = result.events.filterIsInstance<ItemDamage>().filter { it.item == Item.ROCKY_HELMET }
        assertTrue(helmetEvents.isEmpty(), "Rocky Helmet should not fire when no damage is dealt")
    }

    @Test
    fun `Rocky Helmet KOs a low-HP attacker`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // Attacker is at 1 HP — a single 1/6-max-HP recoil will faint it.
        val attacker = PokemonState(charizard, currentHp = 1)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp, item = Item.ROCKY_HELMET)
        val state = BattleState.singles(attacker, defender)

        // Tackle is contact — Rocky Helmet fires. A non-contact move here would not
        // recoil-KO the attacker; this test specifically exercises the contact path.
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.TACKLE),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val faintEvents = result.events.filterIsInstance<com.pokemon.battle.engine.PokemonFainted>()
        assertTrue(
            faintEvents.any { it.slot == com.pokemon.battle.model.Slot.p1() },
            "Rocky Helmet recoil should faint a 1-HP attacker",
        )
    }
}
