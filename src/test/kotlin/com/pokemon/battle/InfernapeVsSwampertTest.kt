package com.pokemon.battle

import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*
import com.pokemon.battle.phase.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Replays the worked example from docs/example-extended.md:
 * Infernape vs Swampert with priority, burn, sandstorm, and Leftovers.
 *
 * Note: our simplified stat formula (no IVs/EVs) produces different absolute
 * numbers than the example doc. The test validates the same event sequence
 * and mechanics — the exact damage values come from our formula with fixed rolls.
 */
class InfernapeVsSwampertTest {

    private val infernapeSpecies = Species(
        name = "Infernape",
        types = listOf(Type.FIRE, Type.FIGHTING),
        baseHp = 76, baseAttack = 104, baseDefense = 71,
        baseSpecialAttack = 104, baseSpecialDefense = 71, baseSpeed = 108
    )

    private val swampertSpecies = Species(
        name = "Swampert",
        types = listOf(Type.WATER, Type.GROUND),
        baseHp = 100, baseAttack = 110, baseDefense = 90,
        baseSpecialAttack = 85, baseSpecialDefense = 90, baseSpeed = 60
    )

    private val infernape = Pokemon(infernapeSpecies, level = 50)
    private val swampert = Pokemon(swampertSpecies, level = 50)

    private val machPunch = Move(
        name = "Mach Punch", type = Type.FIGHTING,
        category = MoveCategory.PHYSICAL, power = 40, priority = 1
    )
    private val earthquake = Move(
        name = "Earthquake", type = Type.GROUND,
        category = MoveCategory.PHYSICAL, power = 100
    )

    private val infernapeMaxHp = calcMaxHp(infernapeSpecies.baseHp, 50)
    private val swampertMaxHp = calcMaxHp(swampertSpecies.baseHp, 50)

    @Test
    fun `full turn with priority, burn, sandstorm, and Leftovers`() {
        val infernapeState = PokemonState(infernape, currentHp = infernapeMaxHp)
        val swampertState = PokemonState(
            swampert,
            currentHp = swampertMaxHp,
            status = StatusCondition.BURN,
            item = Item.LEFTOVERS
        )

        val initialState = BattleState(
            infernapeState, swampertState,
            field = FieldState(weather = Weather.SANDSTORM, weatherTurnsRemaining = 3)
        )
        val choices = TurnChoices(
            p1 = TurnChoice.UseMove(machPunch),
            p2 = TurnChoice.UseMove(earthquake)
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

        // Event 1: Infernape goes first due to priority (not speed)
        val order = assertIs<MoveOrderDecided>(events[0])
        assertEquals(Player.P1, order.firstAttacker)
        assertEquals(OrderReason.PRIORITY, order.reason)

        // Event 2: Infernape attempts Mach Punch
        val attempt1 = assertIs<MoveAttempted>(events[1])
        assertEquals(Player.P1, attempt1.attacker)
        assertEquals("Mach Punch", attempt1.move.name)

        // Event 3: Neutral damage to Swampert (Fighting vs Water/Ground = 1x)
        val damage1 = assertIs<DamageDealt>(events[2])
        assertEquals(Player.P2, damage1.target)
        assertEquals(Effectiveness.NEUTRAL, damage1.effectiveness)

        // Event 4: Swampert attempts Earthquake
        val attempt2 = assertIs<MoveAttempted>(events[3])
        assertEquals(Player.P2, attempt2.attacker)
        assertEquals("Earthquake", attempt2.move.name)

        // Event 5: Super-effective damage to Infernape (Ground vs Fire = 2x, halved by burn)
        val damage2 = assertIs<DamageDealt>(events[4])
        assertEquals(Player.P1, damage2.target)
        assertEquals(Effectiveness.SUPER_EFFECTIVE, damage2.effectiveness)

        // Event 6: Sandstorm damages Infernape (Fire/Fighting not immune)
        val weatherDmg = assertIs<WeatherDamage>(events[5])
        assertEquals(Player.P1, weatherDmg.target)
        assertEquals(Weather.SANDSTORM, weatherDmg.weather)
        assertEquals(infernapeMaxHp / 16, weatherDmg.amount)

        // Event 7: Burn damages Swampert
        val burnDmg = assertIs<StatusDamage>(events[6])
        assertEquals(Player.P2, burnDmg.target)
        assertEquals(StatusCondition.BURN, burnDmg.source)
        assertEquals(swampertMaxHp / 16, burnDmg.amount)

        // Event 8: Leftovers heals Swampert
        val healing = assertIs<ItemHealing>(events[7])
        assertEquals(Player.P2, healing.target)
        assertEquals(Item.LEFTOVERS, healing.item)
        assertEquals(swampertMaxHp / 16, healing.amount)

        // Event 9: Sandstorm ticks down
        val tick = assertIs<WeatherTick>(events[8])
        assertEquals(Weather.SANDSTORM, tick.weather)
        assertEquals(2, tick.turnsRemaining)

        assertEquals(9, events.size, "Expected exactly 9 events")

        // Final state: sandstorm at 2 turns
        assertEquals(Weather.SANDSTORM, result.finalState.field.weather)
        assertEquals(2, result.finalState.field.weatherTurnsRemaining)

        // Swampert: burn and leftovers cancel out (both 1/16 max HP)
        assertEquals(burnDmg.amount, healing.amount, "Burn and Leftovers should cancel")

        // Neither fainted
        assertEquals(false, result.finalState.pokemon1.isFainted)
        assertEquals(false, result.finalState.pokemon2.isFainted)
    }

    @Test
    fun `Swampert is immune to sandstorm via Ground typing`() {
        val swampertState = PokemonState(swampert, currentHp = swampertMaxHp)
        val state = BattleState(
            PokemonState(infernape, currentHp = infernapeMaxHp),
            swampertState,
            field = FieldState(weather = Weather.SANDSTORM, weatherTurnsRemaining = 3)
        )

        // Run just EndOfTurnPhase to isolate weather logic
        val choices = TurnChoices(
            p1 = TurnChoice.UseMove(machPunch),
            p2 = TurnChoice.UseMove(earthquake)
        )
        val events = EndOfTurnPhase().resolve(state, choices)

        // Only Infernape should take weather damage, not Swampert
        val weatherEvents = events.filterIsInstance<WeatherDamage>()
        assertEquals(1, weatherEvents.size)
        assertEquals(Player.P1, weatherEvents[0].target)
    }

    @Test
    fun `ability-based sandstorm immunity works`() {
        // Give Infernape Sand Veil — it should be immune to sandstorm damage
        val infernapeWithSandVeil = PokemonState(
            infernape, currentHp = infernapeMaxHp, ability = Ability.SAND_VEIL
        )
        val state = BattleState(
            infernapeWithSandVeil,
            PokemonState(swampert, currentHp = swampertMaxHp),
            field = FieldState(weather = Weather.SANDSTORM, weatherTurnsRemaining = 3)
        )
        val choices = TurnChoices(
            p1 = TurnChoice.UseMove(machPunch),
            p2 = TurnChoice.UseMove(earthquake)
        )

        val events = EndOfTurnPhase().resolve(state, choices)

        // Neither should take weather damage: Swampert is Ground type, Infernape has Sand Veil
        val weatherEvents = events.filterIsInstance<WeatherDamage>()
        assertEquals(0, weatherEvents.size)
    }

    @Test
    fun `burn halves physical damage`() {
        val attacker = PokemonState(swampert, currentHp = swampertMaxHp, status = StatusCondition.BURN)
        val defender = PokemonState(infernape, currentHp = infernapeMaxHp)

        val withBurn = calculateDamage(attacker, defender, earthquake, roll = { 100 })

        val noBurnAttacker = PokemonState(swampert, currentHp = swampertMaxHp)
        val withoutBurn = calculateDamage(noBurnAttacker, defender, earthquake, roll = { 100 })

        // Burn should roughly halve the damage (integer rounding may make it not exact)
        val ratio = withBurn.damage.toDouble() / withoutBurn.damage.toDouble()
        assert(ratio in 0.45..0.55) {
            "Burn damage ratio should be ~0.5, got $ratio (${withBurn.damage} vs ${withoutBurn.damage})"
        }
    }
}
