package com.pokemon.battle

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.ItemHealing
import com.pokemon.battle.engine.MoveAttempted
import com.pokemon.battle.engine.MoveOrderDecided
import com.pokemon.battle.engine.PipelineState
import com.pokemon.battle.engine.StatusDamage
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.WeatherDamage
import com.pokemon.battle.engine.WeatherTick
import com.pokemon.battle.engine.calculateDamage
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Effectiveness
import com.pokemon.battle.model.FieldState
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.OrderReason
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Species
import com.pokemon.battle.model.StatusCondition
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.Weather
import com.pokemon.battle.model.calcMaxHp
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InfernapeVsSwampertTest {
    private val infernapeSpecies =
        Species(
            name = "Infernape",
            types = listOf(Type.FIRE, Type.FIGHTING),
            baseHp = 76,
            baseAttack = 104,
            baseDefense = 71,
            baseSpecialAttack = 104,
            baseSpecialDefense = 71,
            baseSpeed = 108,
        )

    private val swampertSpecies =
        Species(
            name = "Swampert",
            types = listOf(Type.WATER, Type.GROUND),
            baseHp = 100,
            baseAttack = 110,
            baseDefense = 90,
            baseSpecialAttack = 85,
            baseSpecialDefense = 90,
            baseSpeed = 60,
        )

    private val infernape = Pokemon(infernapeSpecies, level = 50)
    private val swampert = Pokemon(swampertSpecies, level = 50)

    private val machPunch =
        Move(
            name = "Mach Punch",
            type = Type.FIGHTING,
            category = MoveCategory.PHYSICAL,
            power = 40,
            priority = 1,
        )
    private val earthquake =
        Move(
            name = "Earthquake",
            type = Type.GROUND,
            category = MoveCategory.PHYSICAL,
            power = 100,
        )

    private val infernapeMaxHp = calcMaxHp(infernapeSpecies.baseHp, 50)
    private val swampertMaxHp = calcMaxHp(swampertSpecies.baseHp, 50)

    @Test
    @Suppress("LongMethod") // Scenario test intentionally threads a full turn's worth of setup and assertions
    fun `full turn with priority, burn, sandstorm, and Leftovers`() {
        val infernapeState = PokemonState(infernape, currentHp = infernapeMaxHp)
        val swampertState =
            PokemonState(
                swampert,
                currentHp = swampertMaxHp,
                status = StatusCondition.BURN,
                item = Item.LEFTOVERS,
            )

        val initialState =
            BattleState.singles(
                infernapeState,
                swampertState,
                field = FieldState(weather = Weather.SANDSTORM, weatherTurnsRemaining = 3),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(machPunch),
                TurnChoice.UseMove(earthquake),
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

        val order = assertIs<MoveOrderDecided>(events[0])
        assertEquals(Slot.p1(), order.order.first())
        assertEquals(OrderReason.PRIORITY, order.leadReason)

        val attempt1 = assertIs<MoveAttempted>(events[1])
        assertEquals(Slot.p1(), attempt1.attacker)

        val damage1 = assertIs<DamageDealt>(events[2])
        assertEquals(Slot.p2(), damage1.target)
        assertEquals(Effectiveness.NEUTRAL, damage1.effectiveness)

        val attempt2 = assertIs<MoveAttempted>(events[3])
        assertEquals(Slot.p2(), attempt2.attacker)

        val damage2 = assertIs<DamageDealt>(events[4])
        assertEquals(Slot.p1(), damage2.target)
        assertEquals(Effectiveness.SUPER_EFFECTIVE, damage2.effectiveness)

        val weatherDmg = assertIs<WeatherDamage>(events[5])
        assertEquals(Slot.p1(), weatherDmg.target)
        assertEquals(infernapeMaxHp / 16, weatherDmg.amount)

        val burnDmg = assertIs<StatusDamage>(events[6])
        assertEquals(Slot.p2(), burnDmg.target)
        assertEquals(swampertMaxHp / 16, burnDmg.amount)

        val healing = assertIs<ItemHealing>(events[7])
        assertEquals(Slot.p2(), healing.target)
        assertEquals(swampertMaxHp / 16, healing.amount)

        val tick = assertIs<WeatherTick>(events[8])
        assertEquals(2, tick.turnsRemaining)

        assertEquals(9, events.size)
        assertEquals(burnDmg.amount, healing.amount, "Burn and Leftovers should cancel")
        assertEquals(false, result.state.pokemonFor(Slot.p1()).isFainted)
        assertEquals(false, result.state.pokemonFor(Slot.p2()).isFainted)
    }

    @Test
    fun `Swampert is immune to sandstorm via Ground typing`() {
        val state =
            BattleState.singles(
                PokemonState(infernape, currentHp = infernapeMaxHp),
                PokemonState(swampert, currentHp = swampertMaxHp),
                field = FieldState(weather = Weather.SANDSTORM, weatherTurnsRemaining = 3),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(machPunch),
                TurnChoice.UseMove(earthquake),
            )
        val events = EndOfTurnPhase(GenVRegistries).resolve(PipelineState(state), choices).events

        val weatherEvents = events.filterIsInstance<WeatherDamage>()
        assertEquals(1, weatherEvents.size)
        assertEquals(Slot.p1(), weatherEvents[0].target)
    }

    @Test
    fun `ability-based sandstorm immunity works`() {
        val state =
            BattleState.singles(
                PokemonState(infernape, currentHp = infernapeMaxHp, ability = Ability.SAND_VEIL),
                PokemonState(swampert, currentHp = swampertMaxHp),
                field = FieldState(weather = Weather.SANDSTORM, weatherTurnsRemaining = 3),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(machPunch),
                TurnChoice.UseMove(earthquake),
            )
        val events = EndOfTurnPhase(GenVRegistries).resolve(PipelineState(state), choices).events

        assertEquals(0, events.filterIsInstance<WeatherDamage>().size)
    }

    @Test
    fun `burn halves physical damage`() {
        val attacker = PokemonState(swampert, currentHp = swampertMaxHp, status = StatusCondition.BURN)
        val defender = PokemonState(infernape, currentHp = infernapeMaxHp)

        val withBurn = calculateDamage(attacker, defender, earthquake, roll = { 100 })
        val noBurnAttacker = PokemonState(swampert, currentHp = swampertMaxHp)
        val withoutBurn = calculateDamage(noBurnAttacker, defender, earthquake, roll = { 100 })

        val ratio = withBurn.damage.toDouble() / withoutBurn.damage.toDouble()
        assert(ratio in 0.45..0.55) {
            "Burn damage ratio should be ~0.5, got $ratio"
        }
    }
}
