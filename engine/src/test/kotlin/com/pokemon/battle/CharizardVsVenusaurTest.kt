package com.pokemon.battle

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.MoveAttempted
import com.pokemon.battle.engine.MoveOrderDecided
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.calculateDamage
import com.pokemon.battle.model.Effectiveness
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.OrderReason
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Species
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.calcMaxHp
import com.pokemon.battle.model.calcStat
import com.pokemon.battle.model.typeEffectiveness
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Worked example: Charizard KOs Venusaur with Flamethrower.
 *
 * This test is the canonical "simple turn" example — an executable replacement
 * for the former docs/example-simple.md. See diary 093 for the rationale (prose
 * that restates API shapes rots; tests can't).
 */
class CharizardVsVenusaurTest {
    private val charizardSpecies =
        Species(
            name = "Charizard",
            types = listOf(Type.FIRE, Type.FLYING),
            baseHp = 78,
            baseAttack = 84,
            baseDefense = 78,
            baseSpecialAttack = 109,
            baseSpecialDefense = 85,
            baseSpeed = 100,
        )

    private val venusaurSpecies =
        Species(
            name = "Venusaur",
            types = listOf(Type.GRASS, Type.POISON),
            baseHp = 80,
            baseAttack = 82,
            baseDefense = 83,
            baseSpecialAttack = 100,
            baseSpecialDefense = 100,
            baseSpeed = 80,
        )

    private val charizard = Pokemon(charizardSpecies, level = 50)
    private val venusaur = Pokemon(venusaurSpecies, level = 50)

    private val flamethrower =
        Move(
            name = "Flamethrower",
            type = Type.FIRE,
            category = MoveCategory.SPECIAL,
            power = 90,
        )
    private val sludgeBomb =
        Move(
            name = "Sludge Bomb",
            type = Type.POISON,
            category = MoveCategory.SPECIAL,
            power = 90,
        )

    @Test
    fun `Charizard outspeeds and KOs Venusaur with super-effective Flamethrower`() {
        val charizardState = PokemonState(charizard, currentHp = charizard.maxHp)
        // Start Venusaur at 130 HP — our damage formula gives ~133 max roll,
        // which KOs from 130 but not from full 155. See damage range test for details.
        val venusaurState = PokemonState(venusaur, currentHp = 130)

        val initialState = BattleState.singles(charizardState, venusaurState)
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(flamethrower),
                TurnChoice.UseMove(sludgeBomb),
            )

        val fixedRoll: (IntRange) -> Int = { 100 }

        val pipeline =
            TurnPipeline(
                listOf(
                    MoveOrderPhase(GenVRegistries),
                    MoveExecutionPhase(GenVRegistries, roll = fixedRoll),
                    EndOfTurnPhase(GenVRegistries),
                ),
            )

        val result = pipeline.resolveToCompletion(initialState, choices)
        val events = result.events

        // Event 1: Charizard goes first (higher speed)
        val order = events[0] as MoveOrderDecided
        assertEquals(Slot.p1(), order.order.first())
        assertEquals(OrderReason.SPEED, order.leadReason)

        // Event 2: Charizard attempts Flamethrower
        val attempt = events[1] as MoveAttempted
        assertEquals(Slot.p1(), attempt.attacker)
        assertEquals("Flamethrower", attempt.move.name)

        // Event 3: Super-effective damage
        val damage = events[2] as DamageDealt
        assertEquals(Slot.p2(), damage.target)
        assertEquals(Effectiveness.SUPER_EFFECTIVE, damage.effectiveness)
        assertTrue(damage.amount > 0, "Damage should be positive")

        // Event 4: Venusaur faints
        val faint = events[3] as PokemonFainted
        assertEquals(Slot.p2(), faint.slot)

        // Venusaur's move is skipped because it fainted — no more events from MoveExecutionPhase
        // EndOfTurnPhase emits nothing
        assertEquals(4, events.size, "Expected exactly 4 events: order, attempt, damage, faint")

        // Final state checks
        assertEquals(0, result.state.pokemonFor(Slot.p2()).currentHp, "Venusaur should have 0 HP")
        assertTrue(result.state.pokemonFor(Slot.p2()).isFainted)
        assertEquals(charizardState.currentHp, result.state.pokemonFor(Slot.p1()).currentHp, "Charizard should be untouched")
    }

    @Test
    fun `damage calc produces super-effective STAB Flamethrower damage in expected range`() {
        val charizardState = PokemonState(charizard, currentHp = charizard.maxHp)
        val venusaurState = PokemonState(venusaur, currentHp = venusaur.maxHp)

        // Fire vs Grass/Poison = 2x, STAB = 1.5x
        val minResult = calculateDamage(charizardState, venusaurState, flamethrower, roll = { 85 })
        val maxResult = calculateDamage(charizardState, venusaurState, flamethrower, roll = { 100 })

        assertEquals(Effectiveness.SUPER_EFFECTIVE, minResult.effectiveness)
        assertEquals(Effectiveness.SUPER_EFFECTIVE, maxResult.effectiveness)

        // Exact values from our formula at fixed rolls (31 IVs / 0 EVs / neutral nature).
        val midResult = calculateDamage(charizardState, venusaurState, flamethrower, roll = { 92 })
        assertEquals(113, minResult.damage, "Min roll (85) damage")
        assertEquals(123, midResult.damage, "Mid roll (92) damage")
        assertEquals(133, maxResult.damage, "Max roll (100) damage")
    }

    @Test
    fun `type effectiveness is correct for Fire vs Grass-Poison`() {
        val multiplier = typeEffectiveness(Type.FIRE, listOf(Type.GRASS, Type.POISON))
        assertEquals(2.0, multiplier, "Fire vs Grass (2x) * Poison (1x) = 2x")
    }

    @Test
    fun `stat calculation matches expected values at level 50`() {
        // With default 31 IVs, 0 EVs, neutral nature:
        assertEquals(120, calcStat(100, 50))
        assertEquals(100, calcStat(80, 50))
        assertEquals(153, calcMaxHp(78, 50))
    }
}
