package com.pokemon.battle

import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.Gen9VgcTeraRuleset
import com.pokemon.battle.engine.GimmickUsed
import com.pokemon.battle.engine.NationalDexRuleset
import com.pokemon.battle.engine.PokemonChampionsRuleset
import com.pokemon.battle.model.GimmickKind
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.UsedGimmick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GimmickStateTest {
    private val pokedex = Pokedex.loadFromClasspath()

    private fun baseState() =
        BattleState.singles(
            PokemonState(Pokemon(pokedex["Charizard"]!!, level = 50), currentHp = 100),
            PokemonState(Pokemon(pokedex["Venusaur"]!!, level = 50), currentHp = 100),
        )

    // ============================================================
    // Recording & lookup
    // ============================================================

    @Test
    fun `GimmickUsed event records in gimmicksUsedBySide`() {
        val state = baseState().copy(ruleset = PokemonChampionsRuleset)
        val event = GimmickUsed(GimmickKind.MEGA, Slot.p1())
        val after = event.apply(state)

        assertEquals(1, after.gimmicksUsedBy(Side.SIDE_1).size)
        val record = after.gimmicksUsedBy(Side.SIDE_1).first()
        assertEquals(GimmickKind.MEGA, record.kind)
        assertEquals(Slot.p1(), record.slot)
        assertEquals(state.turn, record.turn)

        // Other side unaffected
        assertTrue(after.gimmicksUsedBy(Side.SIDE_2).isEmpty())
    }

    // ============================================================
    // Ruleset policies
    // ============================================================

    @Test
    fun `NoGimmicksRuleset rejects all kinds`() {
        val state = baseState() // default ruleset = NoGimmicksRuleset
        GimmickKind.entries.forEach { kind ->
            assertFalse(state.canUseGimmick(kind, Side.SIDE_1), "NoGimmicks should reject $kind")
        }
    }

    @Test
    fun `PokemonChampionsRuleset allows first gimmick, rejects second`() {
        var state = baseState().copy(ruleset = PokemonChampionsRuleset)

        // First Mega is allowed
        assertTrue(state.canUseGimmick(GimmickKind.MEGA, Side.SIDE_1))

        // Record it
        state = GimmickUsed(GimmickKind.MEGA, Slot.p1()).apply(state)

        // Second gimmick of any kind on the same side: rejected
        assertFalse(state.canUseGimmick(GimmickKind.MEGA, Side.SIDE_1))
        assertFalse(state.canUseGimmick(GimmickKind.Z_MOVE, Side.SIDE_1))
        assertFalse(state.canUseGimmick(GimmickKind.TERA, Side.SIDE_1))

        // Other side still free
        assertTrue(state.canUseGimmick(GimmickKind.MEGA, Side.SIDE_2))
    }

    @Test
    fun `NationalDexRuleset allows one of each kind per side`() {
        var state = baseState().copy(ruleset = NationalDexRuleset)

        // All kinds legal initially
        GimmickKind.entries.forEach { kind ->
            assertTrue(state.canUseGimmick(kind, Side.SIDE_1), "All kinds initially legal, not $kind")
        }

        // Use Mega
        state = GimmickUsed(GimmickKind.MEGA, Slot.p1()).apply(state)

        // Mega now rejected, others still legal
        assertFalse(state.canUseGimmick(GimmickKind.MEGA, Side.SIDE_1))
        assertTrue(state.canUseGimmick(GimmickKind.Z_MOVE, Side.SIDE_1))
        assertTrue(state.canUseGimmick(GimmickKind.DYNAMAX, Side.SIDE_1))

        // Use Z
        state = GimmickUsed(GimmickKind.Z_MOVE, Slot.p1()).apply(state)

        assertFalse(state.canUseGimmick(GimmickKind.Z_MOVE, Side.SIDE_1))
        assertTrue(state.canUseGimmick(GimmickKind.DYNAMAX, Side.SIDE_1))
    }

    @Test
    fun `Gen9VgcTeraRuleset allows only Tera, once`() {
        var state = baseState().copy(ruleset = Gen9VgcTeraRuleset)

        assertTrue(state.canUseGimmick(GimmickKind.TERA, Side.SIDE_1))
        assertFalse(state.canUseGimmick(GimmickKind.MEGA, Side.SIDE_1))
        assertFalse(state.canUseGimmick(GimmickKind.Z_MOVE, Side.SIDE_1))

        state = GimmickUsed(GimmickKind.TERA, Slot.p1()).apply(state)

        assertFalse(state.canUseGimmick(GimmickKind.TERA, Side.SIDE_1))
    }

    // ============================================================
    // Purely bookkeeping — shouldn't touch anything else
    // ============================================================

    @Test
    fun `GimmickUsed does not modify Pokemon state or HP`() {
        val state = baseState().copy(ruleset = PokemonChampionsRuleset)
        val p1Before = state.pokemonFor(Slot.p1())
        val p2Before = state.pokemonFor(Slot.p2())

        val after = GimmickUsed(GimmickKind.MEGA, Slot.p1()).apply(state)

        assertEquals(p1Before, after.pokemonFor(Slot.p1()))
        assertEquals(p2Before, after.pokemonFor(Slot.p2()))
    }

    @Test
    fun `UsedGimmick data class preserves kind slot and turn`() {
        val used = UsedGimmick(GimmickKind.TERA, Slot.p1(), turn = 5)
        assertEquals(GimmickKind.TERA, used.kind)
        assertEquals(Slot.p1(), used.slot)
        assertEquals(5, used.turn)
    }
}
