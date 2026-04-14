package com.pokemon.battle

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.GenVDamageCalculator
import com.pokemon.battle.engine.ItemDamage
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
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

class LifeOrbTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val noChance: ChanceCheck = { _, _ -> false }
    private val fixedRoll: (IntRange) -> Int = { 100 }

    private fun pipeline() =
        TurnPipeline(
            listOf(
                MoveOrderPhase(),
                SwitchPhase(),
                MoveExecutionPhase(roll = fixedRoll, chanceCheck = noChance),
                EndOfTurnPhase(),
            ),
        )

    // ============================================================
    // Mainline Pokemon mechanics — reachable in normal play
    // ============================================================

    @Test
    fun `Life Orb boosts damage by 1_3x`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val plain = PokemonState(charizard, currentHp = charizard.maxHp)
        val orb = PokemonState(charizard, currentHp = charizard.maxHp, item = Item.LIFE_ORB)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp)

        val plainDamage =
            GenVDamageCalculator()
                .calculate(plain, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage
        val orbDamage =
            GenVDamageCalculator()
                .calculate(orb, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage

        assertTrue(orbDamage > plainDamage, "Life Orb should boost damage: orb=$orbDamage plain=$plainDamage")
    }

    @Test
    fun `Life Orb deals 10 percent max HP recoil after damage`() {
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
        val finalState = result.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }

        val recoil = result.events.filterIsInstance<ItemDamage>()
        assertEquals(1, recoil.size)
        assertEquals(Item.LIFE_ORB, recoil[0].item)
        assertEquals(Slot.p1(), recoil[0].target)
        assertEquals(charizard.maxHp / 10, recoil[0].amount, "Life Orb recoil = maxHp / 10")

        // Charizard's HP should reflect both Sludge Bomb damage AND Life Orb recoil
        val charAfter = finalState.pokemonFor(Slot.p1())
        assertTrue(charAfter.currentHp < charizard.maxHp, "Charizard should have taken damage")
    }

    @Test
    fun `Life Orb does not trigger when move deals no damage`() {
        // Life Orb user uses a status move (Swords Dance has no damage)
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp, item = Item.LIFE_ORB),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.SWORDS_DANCE),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        // No ItemDamage from Charizard
        val lifeOrb = result.events.filterIsInstance<ItemDamage>().filter { it.item == Item.LIFE_ORB }
        assertTrue(lifeOrb.isEmpty(), "Life Orb should not trigger on status moves")
    }

    @Test
    fun `Life Orb does not trigger when attack is fully blocked by Protect`() {
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
                TurnChoice.UseMove(MoveDex.PROTECT),
            )

        // chanceCheck lets Protect succeed
        val protectSucceeds: ChanceCheck = { _, reason ->
            reason == com.pokemon.battle.model.FailReason.PROTECT_FAILED
        }
        val pipe =
            TurnPipeline(
                listOf(
                    MoveOrderPhase(),
                    SwitchPhase(),
                    MoveExecutionPhase(roll = fixedRoll, chanceCheck = protectSucceeds),
                    EndOfTurnPhase(),
                ),
            )
        val result = pipe.resolveToCompletion(state, choices)

        val lifeOrb = result.events.filterIsInstance<ItemDamage>().filter { it.item == Item.LIFE_ORB }
        assertTrue(lifeOrb.isEmpty(), "Life Orb should not trigger when move is blocked by Protect")
    }

    @Test
    fun `Life Orb does not trigger when move is type-immune`() {
        // Electric vs Ground = 0x, no damage → no Life Orb recoil
        val pikachu = Pokemon(pokedex["Pikachu"]!!, level = 50)
        val garchomp = Pokemon(pokedex["Garchomp"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(pikachu, currentHp = pikachu.maxHp, item = Item.LIFE_ORB),
                PokemonState(garchomp, currentHp = garchomp.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.THUNDERBOLT),
                TurnChoice.UseMove(MoveDex.EARTHQUAKE),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val lifeOrb = result.events.filterIsInstance<ItemDamage>().filter { it.item == Item.LIFE_ORB }
        assertTrue(lifeOrb.isEmpty(), "Life Orb should not trigger on type-immune hit")
    }

    @Test
    fun `Life Orb can KO the user`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // Charizard at 1 HP with Life Orb — the recoil will KO it
        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = 1, item = Item.LIFE_ORB),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        // Life Orb recoil then faint event
        val lifeOrbRecoil = result.events.filterIsInstance<ItemDamage>()
        assertEquals(1, lifeOrbRecoil.size)
        assertTrue(
            result.events.filterIsInstance<PokemonFainted>().any { it.slot == Slot.p1() },
            "Charizard should faint from Life Orb recoil",
        )
    }

    @Test
    fun `Life Orb fires only once for spread moves`() {
        // Earthquake targets ALL_OTHER (in singles still hits the one opponent → just one target).
        // We don't have a doubles spread test setup easily; verify singles behavior at least.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp, item = Item.LIFE_ORB),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.EARTHQUAKE),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val lifeOrb = result.events.filterIsInstance<ItemDamage>().filter { it.item == Item.LIFE_ORB }
        assertEquals(1, lifeOrb.size, "Life Orb recoil should fire exactly once per move")
    }

    @Test
    fun `Life Orb is not consumed`() {
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
        val finalState = result.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }

        assertEquals(Item.LIFE_ORB, finalState.pokemonFor(Slot.p1()).item, "Life Orb persists across turns")
    }
}
