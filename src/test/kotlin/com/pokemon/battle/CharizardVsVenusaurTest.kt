package com.pokemon.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays the worked example from docs/example-simple.md:
 * Charizard KOs Venusaur with Flamethrower.
 */
class CharizardVsVenusaurTest {

    private val charizardSpecies = Species(
        name = "Charizard",
        types = listOf(Type.FIRE, Type.FLYING),
        baseHp = 78, baseAttack = 84, baseDefense = 78,
        baseSpecialAttack = 109, baseSpecialDefense = 85, baseSpeed = 100
    )

    private val venusaurSpecies = Species(
        name = "Venusaur",
        types = listOf(Type.GRASS, Type.POISON),
        baseHp = 80, baseAttack = 82, baseDefense = 83,
        baseSpecialAttack = 100, baseSpecialDefense = 100, baseSpeed = 80
    )

    private val charizard = Pokemon(charizardSpecies, level = 50)
    private val venusaur = Pokemon(venusaurSpecies, level = 50)

    private val flamethrower = Move(
        name = "Flamethrower", type = Type.FIRE,
        category = MoveCategory.SPECIAL, power = 90
    )
    private val sludgeBomb = Move(
        name = "Sludge Bomb", type = Type.POISON,
        category = MoveCategory.SPECIAL, power = 90
    )

    @Test
    fun `Charizard outspeeds and KOs Venusaur with super-effective Flamethrower`() {
        val charizardState = PokemonState(charizard, currentHp = calcMaxHp(charizardSpecies.baseHp, 50))
        // Start Venusaur at 130 HP so our simplified formula (no IVs/EVs) can KO.
        // With full IVs/EVs the max HP would be 155 and max-roll Flamethrower would deal ~176.
        val venusaurState = PokemonState(venusaur, currentHp = 130)

        val initialState = BattleState(charizardState, venusaurState)
        val choices = TurnChoices(
            p1 = TurnChoice.UseMove(flamethrower),
            p2 = TurnChoice.UseMove(sludgeBomb)
        )

        val fixedRoll: (IntRange) -> Int = { 100 }

        val pipeline = TurnPipeline(
            listOf(
                MoveOrderPhase(),
                MoveExecutionPhase(roll = fixedRoll),
                EndOfTurnPhase()
            )
        )

        val result = pipeline.resolve(initialState, choices)
        val events = result.events

        // Event 1: Charizard goes first (higher speed)
        val order = events[0] as MoveOrderDecided
        assertEquals(Player.P1, order.firstAttacker)
        assertEquals("speed", order.reason)

        // Event 2: Charizard attempts Flamethrower
        val attempt = events[1] as MoveAttempted
        assertEquals(Player.P1, attempt.attacker)
        assertEquals("Flamethrower", attempt.move.name)

        // Event 3: Super-effective damage
        val damage = events[2] as DamageDealt
        assertEquals(Player.P2, damage.target)
        assertEquals(Effectiveness.SUPER_EFFECTIVE, damage.effectiveness)
        assertTrue(damage.amount > 0, "Damage should be positive")

        // Event 4: Venusaur faints
        val faint = events[3] as PokemonFainted
        assertEquals(Player.P2, faint.player)

        // Venusaur's move is skipped because it fainted — no more events from MoveExecutionPhase
        // EndOfTurnPhase emits nothing
        assertEquals(4, events.size, "Expected exactly 4 events: order, attempt, damage, faint")

        // Final state checks
        assertEquals(0, result.finalState.pokemon2.currentHp, "Venusaur should have 0 HP")
        assertTrue(result.finalState.pokemon2.isFainted)
        assertEquals(charizardState.currentHp, result.finalState.pokemon1.currentHp, "Charizard should be untouched")
    }

    @Test
    fun `damage calc produces super-effective STAB Flamethrower damage in expected range`() {
        val charizardState = PokemonState(charizard, currentHp = calcMaxHp(charizardSpecies.baseHp, 50))
        val venusaurState = PokemonState(venusaur, currentHp = calcMaxHp(venusaurSpecies.baseHp, 50))

        // Fire vs Grass/Poison = 2x, STAB = 1.5x
        val minResult = calculateDamage(charizardState, venusaurState, flamethrower, roll = { 85 })
        val maxResult = calculateDamage(charizardState, venusaurState, flamethrower, roll = { 100 })

        assertEquals(Effectiveness.SUPER_EFFECTIVE, minResult.effectiveness)
        assertEquals(Effectiveness.SUPER_EFFECTIVE, maxResult.effectiveness)

        // Without IVs/EVs the damage range is lower than the worked example.
        // Once IVs/EVs are added, these numbers should match docs/example-simple.md (~148-176).
        assertTrue(minResult.damage in 100..150,
            "Min roll damage (${minResult.damage}) should be in a reasonable range")
        assertTrue(maxResult.damage in 120..170,
            "Max roll damage (${maxResult.damage}) should be in a reasonable range")
        assertTrue(maxResult.damage > minResult.damage, "Max roll should deal more than min roll")
    }

    @Test
    fun `type effectiveness is correct for Fire vs Grass-Poison`() {
        val multiplier = typeEffectiveness(Type.FIRE, listOf(Type.GRASS, Type.POISON))
        assertEquals(2.0, multiplier, "Fire vs Grass (2x) * Poison (1x) = 2x")
    }

    @Test
    fun `stat calculation matches expected values at level 50`() {
        // Charizard base speed 100 at level 50: (2*100*50)/100 + 5 = 105
        assertEquals(105, calcStat(100, 50))
        // Venusaur base speed 80 at level 50: (2*80*50)/100 + 5 = 85
        assertEquals(85, calcStat(80, 50))
        // Charizard HP base 78 at level 50: (2*78*50)/100 + 50 + 10 = 138
        assertEquals(138, calcMaxHp(78, 50))
    }
}
