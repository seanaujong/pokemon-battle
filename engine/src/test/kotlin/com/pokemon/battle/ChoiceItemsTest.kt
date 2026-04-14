package com.pokemon.battle

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.GenVDamageCalculator
import com.pokemon.battle.engine.GenVSpeedResolver
import com.pokemon.battle.engine.MoveOrderDecided
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.VolatileAdded
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Volatile
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChoiceItemsTest {
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
    // Mainline Pokemon mechanics
    // ============================================================

    // --- Choice Band (Physical 1.5x) ---

    @Test
    fun `Choice Band boosts physical damage`() {
        val infernape = Pokemon(pokedex["Infernape"]!!, level = 50)
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50)

        val plain = PokemonState(infernape, currentHp = infernape.maxHp)
        val band = PokemonState(infernape, currentHp = infernape.maxHp, item = Item.CHOICE_BAND)
        val defender = PokemonState(swampert, currentHp = swampert.maxHp)

        val plainDmg =
            GenVDamageCalculator()
                .calculate(plain, defender, MoveDex.EARTHQUAKE, fixedRoll, 1.0, false, null).damage
        val bandDmg =
            GenVDamageCalculator()
                .calculate(band, defender, MoveDex.EARTHQUAKE, fixedRoll, 1.0, false, null).damage

        assertTrue(bandDmg > plainDmg, "Choice Band should boost physical damage: band=$bandDmg plain=$plainDmg")
    }

    @Test
    fun `Choice Band does not boost special damage`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val plain = PokemonState(charizard, currentHp = charizard.maxHp)
        val band = PokemonState(charizard, currentHp = charizard.maxHp, item = Item.CHOICE_BAND)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp)

        val plainDmg =
            GenVDamageCalculator()
                .calculate(plain, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage
        val bandDmg =
            GenVDamageCalculator()
                .calculate(band, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage

        assertEquals(plainDmg, bandDmg, "Choice Band should NOT boost special damage")
    }

    // --- Choice Specs (Special 1.5x) ---

    @Test
    fun `Choice Specs boosts special damage`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val plain = PokemonState(charizard, currentHp = charizard.maxHp)
        val specs = PokemonState(charizard, currentHp = charizard.maxHp, item = Item.CHOICE_SPECS)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp)

        val plainDmg =
            GenVDamageCalculator()
                .calculate(plain, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage
        val specsDmg =
            GenVDamageCalculator()
                .calculate(specs, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage

        assertTrue(specsDmg > plainDmg, "Choice Specs should boost special damage: specs=$specsDmg plain=$plainDmg")
    }

    @Test
    fun `Choice Specs does not boost physical damage`() {
        val infernape = Pokemon(pokedex["Infernape"]!!, level = 50)
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50)

        val plain = PokemonState(infernape, currentHp = infernape.maxHp)
        val specs = PokemonState(infernape, currentHp = infernape.maxHp, item = Item.CHOICE_SPECS)
        val defender = PokemonState(swampert, currentHp = swampert.maxHp)

        val plainDmg =
            GenVDamageCalculator()
                .calculate(plain, defender, MoveDex.EARTHQUAKE, fixedRoll, 1.0, false, null).damage
        val specsDmg =
            GenVDamageCalculator()
                .calculate(specs, defender, MoveDex.EARTHQUAKE, fixedRoll, 1.0, false, null).damage

        assertEquals(plainDmg, specsDmg, "Choice Specs should NOT boost physical damage")
    }

    // --- Choice Scarf (Speed 1.5x) ---

    @Test
    fun `Choice Scarf boosts speed 1_5x`() {
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val plain = PokemonState(venusaur, currentHp = venusaur.maxHp)
        val scarf = PokemonState(venusaur, currentHp = venusaur.maxHp, item = Item.CHOICE_SCARF)

        val plainState = BattleState.singles(plain, plain)
        val scarfState = BattleState.singles(scarf, plain)
        val plainSpeed = GenVSpeedResolver.effectiveSpeed(plain, Slot.p1(), plainState)
        val scarfSpeed = GenVSpeedResolver.effectiveSpeed(scarf, Slot.p1(), scarfState)

        assertTrue(scarfSpeed > plainSpeed, "Scarf should increase speed: scarf=$scarfSpeed plain=$plainSpeed")
        assertEquals(plainSpeed * 1.5, scarfSpeed, absoluteTolerance = 0.01)
    }

    @Test
    fun `Choice Scarf lets slower Pokemon outspeed faster one`() {
        // Charizard (speed 100) vs Venusaur (speed 80). Venusaur with Scarf = 120 effective.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp, item = Item.CHOICE_SCARF),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val order = result.events.filterIsInstance<MoveOrderDecided>().first()

        assertEquals(Slot.p2(), order.order.first(), "Venusaur with Scarf should outspeed Charizard")
    }

    // --- Choice lock volatile ---

    @Test
    fun `using a damaging move with Choice Band emits ChoiceLocked`() {
        val infernape = Pokemon(pokedex["Infernape"]!!, level = 50)
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(infernape, currentHp = infernape.maxHp, item = Item.CHOICE_BAND),
                PokemonState(swampert, currentHp = swampert.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.EARTHQUAKE),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val lockEvents =
            result.events.filterIsInstance<VolatileAdded>()
                .filter { it.volatile is Volatile.ChoiceLocked }
        assertEquals(1, lockEvents.size, "Should lock to the move that was used")
        val locked = lockEvents[0].volatile as Volatile.ChoiceLocked
        assertEquals(MoveDex.EARTHQUAKE, locked.move)
    }

    @Test
    fun `ChoiceLocked is cleared on switch-out`() {
        val infernape = Pokemon(pokedex["Infernape"]!!, level = 50)
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // Infernape already has ChoiceLocked volatile
        val state =
            BattleState.singles(
                PokemonState(
                    infernape,
                    currentHp = infernape.maxHp,
                    item = Item.CHOICE_BAND,
                    volatiles = setOf(Volatile.ChoiceLocked(MoveDex.EARTHQUAKE)),
                ),
                PokemonState(swampert, currentHp = swampert.maxHp),
                p1Bench = listOf(PokemonState(venusaur, currentHp = venusaur.maxHp)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val finalState = result.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }

        // Infernape is now on the bench; its ChoiceLocked should be gone
        val benched =
            finalState.benchFor(com.pokemon.battle.model.Side.SIDE_1)
                .first { it.pokemon.species.name == "Infernape" }
        assertTrue(
            benched.volatiles.none { it is Volatile.ChoiceLocked },
            "Switch-out should clear ChoiceLocked",
        )
    }

    @Test
    fun `ChoiceLocked is not re-emitted if already present`() {
        val infernape = Pokemon(pokedex["Infernape"]!!, level = 50)
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50)

        // Infernape already locked to Earthquake (as it should be after turn 1)
        val state =
            BattleState.singles(
                PokemonState(
                    infernape,
                    currentHp = infernape.maxHp,
                    item = Item.CHOICE_BAND,
                    volatiles = setOf(Volatile.ChoiceLocked(MoveDex.EARTHQUAKE)),
                ),
                PokemonState(swampert, currentHp = swampert.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.EARTHQUAKE),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val newLockEvents =
            result.events.filterIsInstance<VolatileAdded>()
                .filter { it.volatile is Volatile.ChoiceLocked }
        assertTrue(newLockEvents.isEmpty(), "Should not re-emit ChoiceLocked when already locked")
    }
}
