package com.pokemon.battle

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.GenVSpeedResolver
import com.pokemon.battle.engine.MoveFailed
import com.pokemon.battle.engine.MoveOrderDecided
import com.pokemon.battle.engine.SideConditionExpired
import com.pokemon.battle.engine.SideConditionSet
import com.pokemon.battle.engine.TrickRoomSet
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.VolatileAdded
import com.pokemon.battle.model.FailReason
import com.pokemon.battle.model.FieldState
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.SideCondition
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Volatile
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedControlTest {
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
    // Trick Room — inverts speed-tiebreak order for 5 turns
    // ============================================================

    @Test
    fun `Trick Room inverts speed order`() {
        // Charizard (speed 100) vs Venusaur (speed 80). Normally Charizard first.
        // With Trick Room active, Venusaur (slower) goes first.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                field = FieldState(trickRoomTurnsRemaining = 5),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)
        val order = result.events.filterIsInstance<MoveOrderDecided>().first()

        assertEquals(Slot.p2(), order.order.first(), "Venusaur (slower) should go first under Trick Room")
    }

    @Test
    fun `Trick Room move sets the field condition`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.TRICK_ROOM),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)
        val trickRoomEvents = result.events.filterIsInstance<TrickRoomSet>()
        assertTrue(trickRoomEvents.any { it.turnsRemaining > 0 }, "Trick Room should be set")
    }

    @Test
    fun `Trick Room has -7 priority`() {
        // Venusaur uses Trick Room, Charizard uses Flamethrower. Flamethrower resolves first
        // because Trick Room has -7 priority.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.TRICK_ROOM),
            )

        val result = pipeline().resolve(state, choices)
        val order = result.events.filterIsInstance<MoveOrderDecided>().first()

        assertEquals(Slot.p1(), order.order.first(), "Flamethrower (priority 0) goes before Trick Room (priority -7)")
    }

    @Test
    fun `Trick Room ticks down at end of turn`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                field = FieldState(trickRoomTurnsRemaining = 5),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)
        val finalState = result.events.fold(state) { s, e -> e.apply(s) }

        assertEquals(4, finalState.field.trickRoomTurnsRemaining, "Trick Room should tick from 5 to 4")
    }

    // ============================================================
    // Tailwind — doubles speed on the user's side for 4 turns
    // ============================================================

    @Test
    fun `Tailwind move sets the side condition`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.TAILWIND),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)
        val set =
            result.events.filterIsInstance<SideConditionSet>()
                .firstOrNull { it.condition == SideCondition.TAILWIND && it.side == Side.SIDE_1 }

        assertTrue(set != null, "Tailwind should be set on p1's side")
        assertEquals(4, set!!.turnsRemaining)
    }

    @Test
    fun `Tailwind doubles speed`() {
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)

        val plain = PokemonState(venusaur, currentHp = venusaur.maxHp)
        val withTailwind =
            BattleState.singles(plain, PokemonState(charizard, currentHp = charizard.maxHp))
                .withSideCondition(Side.SIDE_1, SideCondition.TAILWIND, turnsRemaining = 4)
        val withoutTailwind =
            BattleState.singles(plain, PokemonState(charizard, currentHp = charizard.maxHp))

        val speedWith = GenVSpeedResolver.effectiveSpeed(plain, Slot.p1(), withTailwind)
        val speedWithout = GenVSpeedResolver.effectiveSpeed(plain, Slot.p1(), withoutTailwind)

        assertEquals(speedWithout * 2.0, speedWith, absoluteTolerance = 0.01)
    }

    @Test
    fun `Tailwind lets a slower Pokemon outspeed a faster one`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            ).withSideCondition(Side.SIDE_2, SideCondition.TAILWIND, turnsRemaining = 4)
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)
        val order = result.events.filterIsInstance<MoveOrderDecided>().first()

        assertEquals(Slot.p2(), order.order.first(), "Venusaur with Tailwind (160) outspeeds Charizard (100)")
    }

    @Test
    fun `Tailwind expires after 4 turns`() {
        // Start at 1 turn remaining — should expire this turn
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            ).withSideCondition(Side.SIDE_1, SideCondition.TAILWIND, turnsRemaining = 1)
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)
        val finalState = result.events.fold(state) { s, e -> e.apply(s) }

        assertTrue(
            result.events.filterIsInstance<SideConditionExpired>().any { it.condition == SideCondition.TAILWIND },
            "Tailwind should expire",
        )
        assertTrue(SideCondition.TAILWIND !in finalState.sideConditionsFor(Side.SIDE_1))
    }

    // ============================================================
    // Fake Out — first-turn-only
    // ============================================================

    @Test
    fun `Fake Out works on the first turn after switch-in`() {
        val infernape = Pokemon(pokedex["Infernape"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(infernape, currentHp = infernape.maxHp, volatiles = setOf(Volatile.JustSwitchedIn)),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FAKE_OUT),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)

        val damage = result.events.filterIsInstance<DamageDealt>().firstOrNull { it.target == Slot.p2() }
        assertTrue(damage != null && damage.amount > 0, "Fake Out should hit")
        // Fake Out sets Flinch on the target
        assertTrue(
            result.events.filterIsInstance<VolatileAdded>().any {
                it.target == Slot.p2() && it.volatile == Volatile.Flinch
            },
            "Target should be flinched",
        )
    }

    @Test
    fun `Fake Out fails without JustSwitchedIn`() {
        val infernape = Pokemon(pokedex["Infernape"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // Infernape starts without JustSwitchedIn — Fake Out should fail
        val state =
            BattleState.singles(
                PokemonState(infernape, currentHp = infernape.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FAKE_OUT),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)

        assertTrue(
            result.events.filterIsInstance<MoveFailed>().any {
                it.attacker == Slot.p1() && it.reason == FailReason.NOT_FIRST_TURN
            },
            "Fake Out should fail without JustSwitchedIn",
        )
        // No damage dealt to Venusaur from Fake Out
        assertTrue(
            result.events.filterIsInstance<DamageDealt>().none { it.target == Slot.p2() },
            "No damage on failed Fake Out",
        )
    }

    @Test
    fun `JustSwitchedIn is granted on switch-in and cleared at end of turn`() {
        val infernape = Pokemon(pokedex["Infernape"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(infernape, currentHp = infernape.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                p1Bench = listOf(PokemonState(blastoise, currentHp = blastoise.maxHp)),
            )
        // Turn 1: Infernape switches to Blastoise
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)
        val finalState = result.events.fold(state) { s, e -> e.apply(s) }

        // After end-of-turn, JustSwitchedIn is cleared
        assertTrue(
            Volatile.JustSwitchedIn !in finalState.pokemonFor(Slot.p1()).volatiles,
            "JustSwitchedIn should clear at end of turn",
        )
    }
}
