package com.pokemon.battle

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.MoveFailed
import com.pokemon.battle.engine.MoveOrderDecided
import com.pokemon.battle.engine.ProtectBlocked
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.VolatileAdded
import com.pokemon.battle.engine.VolatileRemoved
import com.pokemon.battle.model.FailReason
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtectTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val fixedRoll: (IntRange) -> Int = { 100 }

    /** Lets Protect succeed; all other rolls (paralysis, freeze-thaw, etc.) fail. */
    private val protectSucceeds: ChanceCheck = { _, reason -> reason == FailReason.PROTECT_FAILED }

    private fun pipeline(chanceCheck: ChanceCheck = protectSucceeds) =
        TurnPipeline(
            listOf(
                MoveOrderPhase(),
                SwitchPhase(),
                MoveExecutionPhase(roll = fixedRoll, chanceCheck = chanceCheck),
                EndOfTurnPhase(),
            ),
        )

    // ============================================================
    // Mainline Pokemon mechanics — reachable in normal play
    // ============================================================

    @Test
    fun `Protect blocks a single-target move`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                PokemonState(charizard, currentHp = charizard.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.PROTECT),
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val blocked = result.events.filterIsInstance<ProtectBlocked>()
        assertEquals(1, blocked.size, "Flamethrower should be blocked")
        assertEquals(Slot.p1(), blocked[0].slot)

        val damage = result.events.filterIsInstance<DamageDealt>()
        assertTrue(damage.isEmpty(), "No damage should be dealt")
    }

    @Test
    fun `Protect gains priority - goes before faster attacker`() {
        // Charizard (speed 100) vs Venusaur (speed 80). Without priority, Charizard goes first.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                PokemonState(charizard, currentHp = charizard.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.PROTECT),
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val order = result.events.filterIsInstance<MoveOrderDecided>().first()

        assertEquals(Slot.p1(), order.order.first(), "Protect (+4 priority) should act first")
    }

    @Test
    fun `Protect is cleared at end of turn`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                PokemonState(charizard, currentHp = charizard.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.PROTECT),
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        // End-of-turn clears Protect
        val removed = result.events.filterIsInstance<VolatileRemoved>()
        assertTrue(
            removed.any { it.target == Slot.p1() && it.volatile == Volatile.Protect },
            "Protect should be removed at end of turn",
        )
        val finalState = result.events.fold(state) { s, e -> e.apply(s) }
        assertFalse(Volatile.Protect in finalState.pokemonFor(Slot.p1()).volatiles)
    }

    @Test
    fun `Protect added as volatile during the turn`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                PokemonState(charizard, currentHp = charizard.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.PROTECT),
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val added = result.events.filterIsInstance<VolatileAdded>()
        assertTrue(
            added.any { it.target == Slot.p1() && it.volatile == Volatile.Protect },
            "Protect volatile should be added to the user",
        )
    }

    @Test
    fun `second turn without Protect is vulnerable`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                PokemonState(charizard, currentHp = charizard.maxHp),
            )

        // Turn 1: Venusaur uses Protect and blocks Flamethrower
        val turn1Choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.PROTECT),
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
            )
        val turn1 = pipeline().resolveToCompletion(state, turn1Choices)
        val stateAfterTurn1 = turn1.events.fold(state) { s, e -> e.apply(s) }

        // Turn 2: No Protect, Flamethrower should hit
        val turn2Choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
            )
        val turn2 = pipeline().resolveToCompletion(stateAfterTurn1, turn2Choices)

        val damage = turn2.events.filterIsInstance<DamageDealt>()
        assertTrue(
            damage.any { it.target == Slot.p1() },
            "Venusaur should take damage on turn 2 (Protect cleared)",
        )
    }

    // --- Consecutive-use penalty ---

    @Test
    fun `first Protect always succeeds`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                PokemonState(charizard, currentHp = charizard.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.PROTECT),
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
            )

        // Use a chanceCheck that requires 100% to succeed — first Protect should still pass
        val strict: ChanceCheck = { percent, _ -> percent >= 100 }
        val result = pipeline(strict).resolveToCompletion(state, choices)

        assertTrue(
            result.events.any { it is ProtectBlocked },
            "First Protect should succeed even at 100% threshold",
        )
    }

    @Test
    fun `second consecutive Protect drops to 50 percent chance`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // Start with ProtectCounter = 1 (already used Protect once)
        val state =
            BattleState.singles(
                PokemonState(venusaur, currentHp = venusaur.maxHp, volatiles = setOf(Volatile.ProtectCounter(1))),
                PokemonState(charizard, currentHp = charizard.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.PROTECT),
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
            )

        // chanceCheck that needs >=50% — first use would pass at 100, second at 50, third at 25 fails
        val needsAtLeast50: ChanceCheck = { percent, _ -> percent >= 50 }
        val result = pipeline(needsAtLeast50).resolveToCompletion(state, choices)

        // 50% > 50% threshold succeeds in our test ChanceCheck — Protect goes through
        assertTrue(result.events.any { it is ProtectBlocked }, "Second Protect at 50% should succeed at threshold 50")
    }

    @Test
    fun `third consecutive Protect can fail`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // Start with ProtectCounter = 2 (third use → 100 shr 2 = 25%)
        val state =
            BattleState.singles(
                PokemonState(venusaur, currentHp = venusaur.maxHp, volatiles = setOf(Volatile.ProtectCounter(2))),
                PokemonState(charizard, currentHp = charizard.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.PROTECT),
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
            )

        // Threshold above 25% → Protect fails
        val needsAtLeast50: ChanceCheck = { percent, _ -> percent >= 50 }
        val result = pipeline(needsAtLeast50).resolveToCompletion(state, choices)

        val failed = result.events.filterIsInstance<MoveFailed>()
        assertTrue(
            failed.any { it.attacker == Slot.p1() && it.reason == FailReason.PROTECT_FAILED },
            "Third Protect at 25% should fail above 50 threshold",
        )

        // Counter still increments to 3 (failed attempts still count)
        val finalState = result.events.fold(state) { s, e -> e.apply(s) }
        val counter = finalState.pokemonFor(Slot.p1()).volatiles.filterIsInstance<Volatile.ProtectCounter>().first()
        assertEquals(3, counter.consecutive, "Counter increments even on failure")

        // Flamethrower lands (Protect failed, no Protect volatile)
        assertTrue(result.events.filterIsInstance<DamageDealt>().any { it.target == Slot.p1() })
    }

    @Test
    fun `using a non-Protect move clears the counter`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // Venusaur has counter=2 from prior Protects
        val state =
            BattleState.singles(
                PokemonState(venusaur, currentHp = venusaur.maxHp, volatiles = setOf(Volatile.ProtectCounter(2))),
                PokemonState(charizard, currentHp = charizard.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val finalState = result.events.fold(state) { s, e -> e.apply(s) }

        val counter = finalState.pokemonFor(Slot.p1()).volatiles.filterIsInstance<Volatile.ProtectCounter>()
        assertTrue(counter.isEmpty(), "Counter should be cleared after a non-Protect move")

        // The clearing should be in the event log
        val removed = result.events.filterIsInstance<VolatileRemoved>()
        assertTrue(removed.any { it.volatile is Volatile.ProtectCounter })
    }

    // --- Status-move blocking ---

    @Test
    fun `Protect blocks a status move targeting the user`() {
        // Realistic scenario: Venusaur picks Protect (+4 priority → goes first, gains Volatile.Protect),
        // then Charizard's Growl resolves and gets blocked by the freshly applied Protect.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                PokemonState(charizard, currentHp = charizard.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.PROTECT),
                TurnChoice.UseMove(MoveDex.GROWL),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        // Growl should be blocked (no StatChanged on Venusaur)
        val statChangesOnVenusaur =
            result.events.filterIsInstance<StatChanged>().filter { it.target == Slot.p1() }
        assertTrue(statChangesOnVenusaur.isEmpty(), "Growl should not affect a protected Pokemon")

        val blocked = result.events.filterIsInstance<ProtectBlocked>()
        assertEquals(1, blocked.size, "Growl should be blocked")
        assertEquals(Slot.p1(), blocked[0].slot)
    }

    // ============================================================
    // Custom-format / extensibility — scenarios not reachable in
    // mainline Pokemon, kept to verify engine flexibility for
    // hypothetical custom moves, abilities, or multi-turn effects.
    // ============================================================

    @Test
    fun `Protect user can still apply self-target effects`() {
        // Not reachable in mainline (one move per turn). We set Volatile.Protect directly to:
        //   (a) verify the `target != attackerSlot` clause in applyProtectGate, and
        //   (b) prove the engine supports hypothetical multi-turn protection mechanics
        //       (custom moves, Substitute-like persistence, ability-granted Protect, etc.).
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(venusaur, currentHp = venusaur.maxHp, volatiles = setOf(Volatile.Protect)),
                PokemonState(charizard, currentHp = charizard.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.SWORDS_DANCE),
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        // Flamethrower is blocked, but Venusaur's own Swords Dance goes through
        val blocked = result.events.filterIsInstance<ProtectBlocked>()
        assertEquals(1, blocked.size, "Only the opponent's attack should be blocked")
        assertEquals(Slot.p1(), blocked[0].slot)

        val statChanges = result.events.filterIsInstance<StatChanged>()
        assertTrue(
            statChanges.any { it.target == Slot.p1() && it.stages > 0 },
            "Venusaur's Swords Dance should still boost its own Attack",
        )
    }
}
