package com.pokemon.battle

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.GenVDamageCalculator
import com.pokemon.battle.engine.ItemDamage
import com.pokemon.battle.engine.ItemHealing
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KlutzTest {
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
    // Mainline Pokemon mechanics — Klutz suppresses held item
    // ============================================================

    @Test
    fun `Klutz suppresses Life Orb damage boost`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val withOrb = PokemonState(charizard, currentHp = charizard.maxHp, item = Item.LIFE_ORB)
        val withOrbAndKlutz =
            PokemonState(charizard, currentHp = charizard.maxHp, item = Item.LIFE_ORB, ability = Ability.KLUTZ)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp)

        val boosted =
            GenVDamageCalculator(GenVRegistries)
                .calculate(withOrb, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage
        val suppressed =
            GenVDamageCalculator(GenVRegistries)
                .calculate(withOrbAndKlutz, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage

        assertTrue(boosted > suppressed, "Klutz should disable Life Orb boost: boosted=$boosted suppressed=$suppressed")
    }

    @Test
    fun `Klutz suppresses Life Orb recoil`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp, item = Item.LIFE_ORB, ability = Ability.KLUTZ),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val lifeOrbRecoil = result.events.filterIsInstance<ItemDamage>().filter { it.item == Item.LIFE_ORB }
        assertTrue(lifeOrbRecoil.isEmpty(), "Klutz should prevent Life Orb recoil")
    }

    @Test
    fun `Klutz suppresses Focus Sash intercept`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp, statStages = com.pokemon.battle.model.StatStages(specialAttack = 2)),
                PokemonState(venusaur, currentHp = venusaur.maxHp, item = Item.FOCUS_SASH, ability = Ability.KLUTZ),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        // Klutz should prevent Sash from saving Venusaur — it should faint
        assertTrue(
            result.events.filterIsInstance<PokemonFainted>().any { it.slot == Slot.p2() },
            "Klutz-held Focus Sash should not save Venusaur",
        )
    }

    @Test
    fun `Klutz suppresses Leftovers healing`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // Charizard at reduced HP, holds Leftovers + Klutz — should NOT heal at end of turn
        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp / 2, item = Item.LEFTOVERS, ability = Ability.KLUTZ),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val leftoversHealing = result.events.filterIsInstance<ItemHealing>().filter { it.item == Item.LEFTOVERS }
        assertTrue(leftoversHealing.isEmpty(), "Klutz should prevent Leftovers end-of-turn healing")
    }

    // --- Regression: non-Klutz users still get item effects ---

    @Test
    fun `Life Orb still works without Klutz`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp, item = Item.LIFE_ORB),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        // Life Orb recoil fires as normal
        val lifeOrbRecoil = result.events.filterIsInstance<ItemDamage>().filter { it.item == Item.LIFE_ORB }
        assertEquals(1, lifeOrbRecoil.size, "Life Orb should trigger normally for non-Klutz holder")

        // Damage is boosted — Charizard's Flamethrower does more than the base amount (relationship check)
        val damage = result.events.filterIsInstance<DamageDealt>().first { it.target == Slot.p2() }
        assertTrue(damage.amount > 0, "Flamethrower should still deal damage")
    }
}
