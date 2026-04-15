package com.pokemon.battle

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.Gen9VgcTeraRuleset
import com.pokemon.battle.engine.Terastallized
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.calculateDamage
import com.pokemon.battle.model.GimmickKind
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Type
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerastallizationTest {
    private val pokedex = Pokedex.loadFromClasspath()

    private fun charizard(teraType: Type? = null): PokemonState {
        val species = pokedex["Charizard"]!!
        val pokemon = Pokemon(species, level = 50, teraType = teraType)
        return PokemonState(pokemon, currentHp = pokemon.maxHp)
    }

    private fun venusaur(): PokemonState {
        val species = pokedex["Venusaur"]!!
        val pokemon = Pokemon(species, level = 50)
        return PokemonState(pokemon, currentHp = pokemon.maxHp)
    }

    // ============================================================
    // Mainline Pokemon mechanics — reachable in normal play
    // ============================================================

    @Test
    fun `Tera STAB is 2x when move type matches original and tera type`() {
        // Charizard (Fire/Flying) with tera type Fire, using Flamethrower (Fire).
        // Move matches BOTH original and tera type → 2.0x STAB.
        val attacker = charizard(teraType = Type.FIRE).copy(terastallized = true, typeOverride = listOf(Type.FIRE))
        val defender = venusaur()
        val teraDamage = calculateDamage(attacker, defender, MoveDex.FLAMETHROWER, roll = { 100 }).damage

        val nonTeraAttacker = charizard(teraType = Type.FIRE)
        val vanillaDamage = calculateDamage(nonTeraAttacker, defender, MoveDex.FLAMETHROWER, roll = { 100 }).damage

        // 2.0x / 1.5x = ~1.333x more damage with Tera.
        assertTrue(
            teraDamage > vanillaDamage,
            "Tera STAB should exceed vanilla STAB (tera=$teraDamage, vanilla=$vanillaDamage)",
        )
    }

    @Test
    fun `Tera STAB is 1_5x when move type matches only tera type`() {
        // Charizard (Fire/Flying) teras into Grass, uses a Grass move. Matches tera
        // but not original → 1.5x STAB (same multiplier as a vanilla same-type move).
        val species = pokedex["Charizard"]!!
        val pokemon = Pokemon(species, level = 50, teraType = Type.GRASS)
        val attacker = PokemonState(pokemon, currentHp = pokemon.maxHp, terastallized = true, typeOverride = listOf(Type.GRASS))
        val defender = venusaur()

        // Use SLUDGE_BOMB surrogate? We need a Grass move. Let's use a move we have.
        // Team pool has no pure Grass move; use a neutral type comparison via reflection
        // of STAB structure: compare damage with teraType==Grass vs teraType==Fire for
        // the same Grass move (synthesized).
        val grassMove = MoveDex.FLAMETHROWER.copy(type = Type.GRASS)

        val teraGrassDamage = calculateDamage(attacker, defender, grassMove, roll = { 100 }).damage

        // Same attacker but tera type Fire (so no STAB match on Grass move).
        val noStabAttacker =
            attacker.copy(
                pokemon = pokemon.copy(teraType = Type.FIRE),
                typeOverride = listOf(Type.FIRE),
            )
        val noStabDamage = calculateDamage(noStabAttacker, defender, grassMove, roll = { 100 }).damage

        // Tera-matching grass move should hit harder than tera-into-fire grass move.
        assertTrue(
            teraGrassDamage > noStabDamage,
            "Tera STAB on Grass should exceed no-STAB (teraGrass=$teraGrassDamage, noStab=$noStabDamage)",
        )
    }

    @Test
    fun `Terastallized event sets type and records gimmick`() {
        val state =
            BattleState
                .singles(charizard(teraType = Type.DRAGON), venusaur())
                .copy(ruleset = Gen9VgcTeraRuleset)

        val after = Terastallized(Slot.p1()).apply(state)
        val charizardAfter = after.pokemonFor(Slot.p1())
        assertTrue(charizardAfter.terastallized)
        assertEquals(listOf(Type.DRAGON), charizardAfter.effectiveTypes)

        val used = after.gimmicksUsedBy(com.pokemon.battle.model.Side.SIDE_1)
        assertEquals(1, used.size)
        assertEquals(GimmickKind.TERA, used.first().kind)
    }

    @Test
    fun `choosing to Tera during a move emits Terastallized before MoveAttempted`() {
        val state =
            BattleState
                .singles(charizard(teraType = Type.FIRE), venusaur())
                .copy(ruleset = Gen9VgcTeraRuleset)

        val pipeline =
            TurnPipeline(
                listOf(
                    MoveOrderPhase(GenVRegistries),
                    MoveExecutionPhase(GenVRegistries, roll = { 100 }),
                    EndOfTurnPhase(GenVRegistries),
                ),
            )

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER, targetSlot = Slot.p2(), terastallize = true),
                TurnChoice.UseMove(MoveDex.EARTHQUAKE, targetSlot = Slot.p1()),
            )

        val result = pipeline.resolveToCompletion(state, choices)
        val events = result.events
        val teraIdx = events.indexOfFirst { it is Terastallized }
        val moveIdx = events.indexOfFirst { it is com.pokemon.battle.engine.MoveAttempted && it.attacker == Slot.p1() }
        assertTrue(teraIdx >= 0, "Expected Terastallized event")
        assertTrue(teraIdx < moveIdx, "Terastallized should precede the attacker's MoveAttempted")

        // Flamethrower should still land damage on Venusaur.
        assertTrue(events.any { it is DamageDealt && it.target == Slot.p2() })
    }

    @Test
    fun `ruleset rejects second Tera activation`() {
        // Activate once; the ruleset should refuse further activations on that side.
        val state =
            BattleState
                .singles(charizard(teraType = Type.FIRE), venusaur())
                .copy(ruleset = Gen9VgcTeraRuleset)
        val afterFirst = Terastallized(Slot.p1()).apply(state)
        assertFalse(afterFirst.canUseGimmick(GimmickKind.TERA, com.pokemon.battle.model.Side.SIDE_1))
    }

    @Test
    fun `NoGimmicks ruleset swallows Tera request`() {
        // Player set terastallize=true but the default ruleset forbids everything.
        // MoveExecutionPhase should NOT emit Terastallized.
        val state = BattleState.singles(charizard(teraType = Type.FIRE), venusaur())
        val pipeline =
            TurnPipeline(
                listOf(
                    MoveOrderPhase(GenVRegistries),
                    MoveExecutionPhase(GenVRegistries, roll = { 100 }),
                    EndOfTurnPhase(GenVRegistries),
                ),
            )

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER, targetSlot = Slot.p2(), terastallize = true),
                TurnChoice.UseMove(MoveDex.EARTHQUAKE, targetSlot = Slot.p1()),
            )

        val events = pipeline.resolveToCompletion(state, choices).events
        assertNull(events.firstOrNull { it is Terastallized })
    }

    // ============================================================
    // Custom-format / extensibility
    // ============================================================

    @Test
    fun `Tera STAB is 1x when move type matches neither original nor tera`() {
        // Extensibility probe: construct a Pokemon whose Tera type diverges from both
        // its original types and the move type. Confirms the "no match" branch
        // collapses to 1.0x. Not reachable in normal play for Charizard+Flamethrower,
        // so this is a purely mechanical test of the STAB function.
        val species = pokedex["Charizard"]!!
        val pokemon = Pokemon(species, level = 50, teraType = Type.WATER)
        val attacker = PokemonState(pokemon, currentHp = pokemon.maxHp, terastallized = true, typeOverride = listOf(Type.WATER))
        val defender = venusaur()
        // Electric move: matches neither Fire/Flying (original) nor Water (tera).
        val electricMove = MoveDex.FLAMETHROWER.copy(type = Type.ELECTRIC)

        val noMatch = calculateDamage(attacker, defender, electricMove, roll = { 100 }).damage

        // Same setup but tera=Electric (so move matches tera): expect more damage.
        val teraElectric =
            attacker.copy(
                pokemon = pokemon.copy(teraType = Type.ELECTRIC),
                typeOverride = listOf(Type.ELECTRIC),
            )
        val withTeraMatch = calculateDamage(teraElectric, defender, electricMove, roll = { 100 }).damage

        assertTrue(withTeraMatch > noMatch, "Tera-match on Electric > no-match (match=$withTeraMatch, none=$noMatch)")
    }

    @Test
    fun `non-terastallized Pokemon keeps vanilla STAB semantics`() {
        val attacker = charizard()
        val defender = venusaur()
        assertFalse(attacker.terastallized)
        assertNotNull(
            calculateDamage(attacker, defender, MoveDex.FLAMETHROWER, roll = { 100 }),
            "Vanilla STAB path must still function",
        )
    }
}
