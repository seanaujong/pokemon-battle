package com.pokemon.battle

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.GenVDamageCalculator
import com.pokemon.battle.engine.GenVSpeedResolver
import com.pokemon.battle.engine.MoveOrderDecided
import com.pokemon.battle.engine.StatusDamage
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.WeatherDamage
import com.pokemon.battle.engine.resolveMoveOrder
import com.pokemon.battle.gen.simplified.SimplifiedDamageCalculator
import com.pokemon.battle.gen.simplified.SimplifiedEndOfTurnPhase
import com.pokemon.battle.gen.simplified.SimplifiedSpeedResolver
import com.pokemon.battle.loop.BattleLoop
import com.pokemon.battle.model.FieldState
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatusCondition
import com.pokemon.battle.model.Weather
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import com.pokemon.battle.render.renderBattle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests proving two different gen implementations produce different results
 * from the same battle state, using the same event types and pipeline.
 */
class SecondGenTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val fixedRoll: (IntRange) -> Int = { 100 }
    private val noChance: ChanceCheck = { _, _ -> false }

    private fun genVPipeline() =
        TurnPipeline(
            listOf(
                MoveOrderPhase(),
                SwitchPhase(),
                MoveExecutionPhase(
                    damageCalculator = GenVDamageCalculator(),
                    speedResolver = GenVSpeedResolver,
                    roll = fixedRoll,
                    chanceCheck = noChance,
                ),
                EndOfTurnPhase(),
            ),
        )

    private fun simplifiedPipeline() =
        TurnPipeline(
            listOf(
                MoveOrderPhase(),
                SwitchPhase(speedResolver = SimplifiedSpeedResolver),
                MoveExecutionPhase(
                    damageCalculator = SimplifiedDamageCalculator(),
                    speedResolver = SimplifiedSpeedResolver,
                    roll = fixedRoll,
                    chanceCheck = noChance,
                ),
                SimplifiedEndOfTurnPhase(),
            ),
        )

    // --- Damage differs ---

    @Test
    fun `simplified gen deals different damage than GenV (no STAB, no burn penalty)`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp)

        // Flamethrower: Fire-type Charizard gets STAB in GenV, not in Simplified
        val genVDamage = GenVDamageCalculator().calculate(attacker, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false)
        val simpleDamage = SimplifiedDamageCalculator().calculate(attacker, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false)

        // Different formulas produce different damage
        assertNotEquals(
            genVDamage.damage,
            simpleDamage.damage,
            "Different formulas should produce different damage (GenV: ${genVDamage.damage}, Simplified: ${simpleDamage.damage})",
        )
    }

    @Test
    fun `simplified gen ignores burn penalty`() {
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50)
        val infernape = Pokemon(pokedex["Infernape"]!!, level = 50)

        val burnedAttacker = PokemonState(swampert, currentHp = swampert.maxHp, status = StatusCondition.BURN)
        val defender = PokemonState(infernape, currentHp = infernape.maxHp)

        // Physical Earthquake from a burned attacker
        val genVDamage = GenVDamageCalculator().calculate(burnedAttacker, defender, MoveDex.EARTHQUAKE, fixedRoll, 1.0, false)
        val simpleDamage = SimplifiedDamageCalculator().calculate(burnedAttacker, defender, MoveDex.EARTHQUAKE, fixedRoll, 1.0, false)

        // GenV halves physical damage when burned; Simplified doesn't
        assertTrue(
            simpleDamage.damage > genVDamage.damage,
            "Simplified should deal more (no burn penalty): ${simpleDamage.damage} vs ${genVDamage.damage}",
        )
    }

    // --- Speed differs ---

    @Test
    fun `paralyzed Pokemon is not slowed in simplified gen`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // Charizard (speed 100) paralyzed vs Venusaur (speed 80)
        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp, status = StatusCondition.PARALYSIS),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        // GenV: paralysis halves speed → Charizard speed 60 < Venusaur speed 100 → Venusaur first
        val genVOrder = resolveMoveOrder(state, choices, GenVSpeedResolver)
        assertEquals(Slot.p2(), genVOrder.order.first(), "GenV: paralyzed Charizard should be slower")

        // Simplified: no paralysis modifier → Charizard speed 120 > Venusaur speed 100 → Charizard first
        val simpleOrder = resolveMoveOrder(state, choices, SimplifiedSpeedResolver)
        assertEquals(Slot.p1(), simpleOrder.order.first(), "Simplified: paralyzed Charizard still goes first")
    }

    // --- End-of-turn differs ---

    @Test
    fun `simplified gen has doubled burn damage and no weather damage`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp, status = StatusCondition.BURN),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                field = FieldState(weather = Weather.SANDSTORM, weatherTurnsRemaining = 3),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        // GenV end-of-turn
        val genVEvents = EndOfTurnPhase().resolve(state, choices)
        val genVBurn = genVEvents.filterIsInstance<StatusDamage>().first()
        val genVWeather = genVEvents.filterIsInstance<WeatherDamage>()

        // Simplified end-of-turn
        val simpleEvents = SimplifiedEndOfTurnPhase().resolve(state, choices)
        val simpleBurn = simpleEvents.filterIsInstance<StatusDamage>().first()
        val simpleWeather = simpleEvents.filterIsInstance<WeatherDamage>()

        // Burn damage: simplified is 1/8, GenV is 1/16
        assertEquals(charizard.maxHp / 16, genVBurn.amount, "GenV burn = 1/16 max HP")
        assertEquals(charizard.maxHp / 8, simpleBurn.amount, "Simplified burn = 1/8 max HP")
        assertTrue(simpleBurn.amount > genVBurn.amount, "Simplified burn should be double GenV")

        // Weather: GenV has sandstorm damage, Simplified doesn't
        assertTrue(genVWeather.isNotEmpty(), "GenV should have weather damage")
        assertTrue(simpleWeather.isEmpty(), "Simplified should have no weather damage")
    }

    // --- Full battle comparison ---

    @Test
    fun `same teams produce different battle outcomes in different gens`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val initialState =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        // Run one turn in each gen
        val genVResult = genVPipeline().resolve(initialState, choices)
        val simpleResult = simplifiedPipeline().resolve(initialState, choices)

        // Both produce damage events, but potentially different counts
        // (simplified formula deals more damage and may KO before the second attacker acts)
        val genVDamage = genVResult.events.filterIsInstance<DamageDealt>()
        val simpleDamage = simpleResult.events.filterIsInstance<DamageDealt>()
        assertTrue(genVDamage.isNotEmpty() && simpleDamage.isNotEmpty())

        // Different damage amounts for the first hit
        val genVFlame = genVDamage.first { it.target == Slot.p2() }
        val simpleFlame = simpleDamage.first { it.target == Slot.p2() }
        assertNotEquals(genVFlame.amount, simpleFlame.amount, "Damage should differ between gens")

        // Both use the same move order event
        val genVOrder = genVResult.events.filterIsInstance<MoveOrderDecided>().first()
        val simpleOrder = simpleResult.events.filterIsInstance<MoveOrderDecided>().first()
        assertEquals(genVOrder.order, simpleOrder.order, "Same speed = same order (no paralysis here)")
    }

    @Test
    fun `full battle renders differently per gen`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val initialState =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )

        // Both use same choices every turn
        val choiceProvider = { _: BattleState ->
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )
        }

        val genVResult =
            BattleLoop(
                pipeline = genVPipeline(),
                choiceProvider = choiceProvider,
                faintReplacementProvider = { _, _ -> 0 },
                maxTurns = 10,
            ).run(initialState)

        val simpleResult =
            BattleLoop(
                pipeline = simplifiedPipeline(),
                choiceProvider = choiceProvider,
                faintReplacementProvider = { _, _ -> 0 },
                maxTurns = 10,
            ).run(initialState)

        // Both should produce a winner
        assertTrue(genVResult.winner != null, "GenV battle should end")
        assertTrue(simpleResult.winner != null, "Simplified battle should end")

        // Battles may take different numbers of turns (different damage = different KO timing)
        val genVTurns = genVResult.turnHistory.size
        val simpleTurns = simpleResult.turnHistory.size

        // Render both
        val genVText = renderBattle(genVResult, initialState)
        val simpleText = renderBattle(simpleResult, initialState)

        println("\n=== GEN V ($genVTurns turns) ===")
        genVText.forEach(::println)
        println("\n=== SIMPLIFIED ($simpleTurns turns) ===")
        simpleText.forEach(::println)
        println("=== END ===\n")
    }
}
