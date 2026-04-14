package com.pokemon.battle

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.MoveFailed
import com.pokemon.battle.engine.MoveLegality
import com.pokemon.battle.engine.PokemonChampionsRuleset
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.volatileBasedMoveLegality
import com.pokemon.battle.model.FailReason
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for ruleset-driven move-legality enforcement (diary 039). The engine consults
 * [com.pokemon.battle.engine.Ruleset.canUseMove] early in move execution; an illegal
 * choice produces a [MoveFailed] event with the specific [FailReason] rather than being
 * silently executed.
 */
class MoveLegalityTest {
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
    fun `choice-locked user submitting a different move fails with CHOICE_LOCKED`() {
        val infernape = Pokemon(pokedex["Infernape"]!!, level = 50)
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(
                    infernape,
                    currentHp = infernape.maxHp,
                    item = Item.CHOICE_BAND,
                    volatiles = setOf(Volatile.ChoiceLocked(MoveDex.EARTHQUAKE)),
                ),
                PokemonState(swampert, currentHp = swampert.maxHp),
            ).copy(ruleset = PokemonChampionsRuleset)

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val fail =
            result.events.filterIsInstance<MoveFailed>()
                .firstOrNull { it.attacker == Slot.p1() }
        assertNotNull(fail, "Illegal choice-locked move should emit MoveFailed")
        assertEquals(FailReason.CHOICE_LOCKED, fail.reason)

        // No damage dealt by Infernape to Swampert — the move was blocked before execution.
        val p1Damage =
            result.events.filterIsInstance<DamageDealt>()
                .filter { it.target == Slot.p2() }
        assertTrue(p1Damage.isEmpty(), "Locked-out move should not deal damage")
    }

    @Test
    fun `choice-locked user submitting the locked move succeeds`() {
        val infernape = Pokemon(pokedex["Infernape"]!!, level = 50)
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(
                    infernape,
                    currentHp = infernape.maxHp,
                    item = Item.CHOICE_BAND,
                    volatiles = setOf(Volatile.ChoiceLocked(MoveDex.EARTHQUAKE)),
                ),
                PokemonState(swampert, currentHp = swampert.maxHp),
            ).copy(ruleset = PokemonChampionsRuleset)

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.EARTHQUAKE),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val lockFail =
            result.events.filterIsInstance<MoveFailed>()
                .firstOrNull { it.attacker == Slot.p1() && it.reason == FailReason.CHOICE_LOCKED }
        assertNull(lockFail, "Using the locked move should not trigger CHOICE_LOCKED fail")

        val p2Damage =
            result.events.filterIsInstance<DamageDealt>()
                .firstOrNull { it.target == Slot.p2() }
        assertNotNull(p2Damage, "Earthquake against Swampert should land")
        assertTrue(p2Damage.amount > 0, "Earthquake should deal damage")
    }

    @Test
    fun `user without ChoiceLocked can use any move under the ruleset`() {
        val infernape = Pokemon(pokedex["Infernape"]!!, level = 50)
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50)

        // Same ruleset, but no ChoiceLocked volatile — user is free.
        val state =
            BattleState.singles(
                PokemonState(infernape, currentHp = infernape.maxHp),
                PokemonState(swampert, currentHp = swampert.maxHp),
            ).copy(ruleset = PokemonChampionsRuleset)

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val lockFail =
            result.events.filterIsInstance<MoveFailed>()
                .firstOrNull { it.reason == FailReason.CHOICE_LOCKED }
        assertNull(lockFail, "Unlocked user should not trigger CHOICE_LOCKED")
    }

    @Test
    fun `validMovesFor returns only the legal subset under a lock`() {
        val infernape = Pokemon(pokedex["Infernape"]!!, level = 50)
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(
                    infernape,
                    currentHp = infernape.maxHp,
                    item = Item.CHOICE_BAND,
                    volatiles = setOf(Volatile.ChoiceLocked(MoveDex.EARTHQUAKE)),
                ),
                PokemonState(swampert, currentHp = swampert.maxHp),
            ).copy(ruleset = PokemonChampionsRuleset)

        val candidates = listOf(MoveDex.EARTHQUAKE, MoveDex.FLAMETHROWER, MoveDex.ICE_BEAM)
        val legal = state.validMovesFor(Slot.p1(), candidates)

        assertEquals(listOf(MoveDex.EARTHQUAKE), legal, "Only the locked move should be legal")
    }

    // ============================================================
    // Extensibility / corner cases
    // ============================================================

    /**
     * The [volatileBasedMoveLegality] helper is the shared implementation every concrete
     * ruleset delegates to. Verifying it directly (without constructing a ruleset) keeps
     * the helper's contract testable as future volatiles — Disable, Encore, Taunt — are
     * added; each will be an additional branch here. Artificial because callers normally
     * go through `Ruleset.canUseMove`, but the helper is the engine's actual logic.
     */
    @Test
    fun `volatileBasedMoveLegality forbids a non-locked move`() {
        val infernape = Pokemon(pokedex["Infernape"]!!, level = 50)
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(
                    infernape,
                    currentHp = infernape.maxHp,
                    volatiles = setOf(Volatile.ChoiceLocked(MoveDex.EARTHQUAKE)),
                ),
                PokemonState(swampert, currentHp = swampert.maxHp),
            )

        val forbidden = volatileBasedMoveLegality(state, Slot.p1(), MoveDex.FLAMETHROWER)
        assertTrue(forbidden is MoveLegality.Forbidden)
        assertEquals(FailReason.CHOICE_LOCKED, forbidden.reason)

        val allowed = volatileBasedMoveLegality(state, Slot.p1(), MoveDex.EARTHQUAKE)
        assertEquals(MoveLegality.Allowed, allowed)
    }
}
