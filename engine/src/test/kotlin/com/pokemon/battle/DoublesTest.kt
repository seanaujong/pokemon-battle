package com.pokemon.battle

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.ItemHealing
import com.pokemon.battle.engine.MoveAttempted
import com.pokemon.battle.engine.PipelineState
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.StatusDamage
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.WeatherDamage
import com.pokemon.battle.engine.WeatherTick
import com.pokemon.battle.engine.calculateDamage
import com.pokemon.battle.engine.resolveMoveOrder
import com.pokemon.battle.model.FieldState
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.MoveTarget
import com.pokemon.battle.model.OrderReason
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Species
import com.pokemon.battle.model.StatusCondition
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.Weather
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DoublesTest {
    // Four distinct species with different speeds for clear ordering
    private val fast = Species("Fast", listOf(Type.NORMAL), 80, 100, 80, 80, 80, 120)
    private val midFast = Species("MidFast", listOf(Type.NORMAL), 80, 100, 80, 80, 80, 100)
    private val midSlow = Species("MidSlow", listOf(Type.NORMAL), 80, 100, 80, 80, 80, 60)
    private val slow = Species("Slow", listOf(Type.NORMAL), 80, 100, 80, 80, 80, 40)

    private val fireType = Species("FireMon", listOf(Type.FIRE), 80, 100, 80, 80, 80, 80)
    private val groundType = Species("GroundMon", listOf(Type.GROUND), 80, 100, 80, 80, 80, 80)

    private val tackle = Move("Tackle", Type.NORMAL, MoveCategory.PHYSICAL, 40)
    private val earthquake = Move("Earthquake", Type.GROUND, MoveCategory.PHYSICAL, 100, target = MoveTarget.ALL_OTHER)
    private val hyperVoice = Move("Hyper Voice", Type.NORMAL, MoveCategory.SPECIAL, 90, target = MoveTarget.ALL_OPPONENTS)
    private val machPunch = Move("Mach Punch", Type.FIGHTING, MoveCategory.PHYSICAL, 40, priority = 1)

    private fun pokemon(species: Species) = Pokemon(species, level = 50)

    private fun state(pokemon: Pokemon) = PokemonState(pokemon, currentHp = pokemon.maxHp)

    private val fixedRoll: (IntRange) -> Int = { 100 }
    private val noChance: ChanceCheck = { _, _ -> false }

    // --- Basic doubles turn ---

    @Test
    fun `4-slot battle orders all slots by speed`() {
        val battleState =
            BattleState.doubles(
                state(pokemon(fast)),
                state(pokemon(slow)),
                state(pokemon(midFast)),
                state(pokemon(midSlow)),
            )
        val choices =
            TurnChoices(
                mapOf(
                    Slot.p1(0) to TurnChoice.UseMove(tackle),
                    Slot.p1(1) to TurnChoice.UseMove(tackle),
                    Slot.p2(0) to TurnChoice.UseMove(tackle),
                    Slot.p2(1) to TurnChoice.UseMove(tackle),
                ),
            )

        val order = resolveMoveOrder(battleState, choices)

        // Speed: Fast(120) > MidFast(100) > MidSlow(60) > Slow(40)
        assertEquals(4, order.order.size)
        assertEquals(Slot.p1(0), order.order[0]) // Fast
        assertEquals(Slot.p2(0), order.order[1]) // MidFast
        assertEquals(Slot.p2(1), order.order[2]) // MidSlow
        assertEquals(Slot.p1(1), order.order[3]) // Slow
    }

    @Test
    fun `all 4 Pokemon act in a doubles turn`() {
        val battleState =
            BattleState.doubles(
                state(pokemon(fast)),
                state(pokemon(slow)),
                state(pokemon(midFast)),
                state(pokemon(midSlow)),
            )
        val choices =
            TurnChoices(
                mapOf(
                    Slot.p1(0) to TurnChoice.UseMove(tackle),
                    Slot.p1(1) to TurnChoice.UseMove(tackle),
                    Slot.p2(0) to TurnChoice.UseMove(tackle),
                    Slot.p2(1) to TurnChoice.UseMove(tackle),
                ),
            )

        val phase = MoveExecutionPhase(GenVRegistries, roll = fixedRoll, chanceCheck = noChance)
        val events = phase.resolve(PipelineState(battleState), choices).events

        val attempts = events.filterIsInstance<MoveAttempted>()
        assertEquals(4, attempts.size, "All 4 Pokemon should attempt a move")
    }

    // --- Single-target in doubles ---

    @Test
    fun `player can target a specific opponent slot`() {
        val battleState =
            BattleState.doubles(
                state(pokemon(fast)),
                state(pokemon(slow)),
                state(pokemon(midFast)),
                state(pokemon(midSlow)),
            )
        // P1 slot 0 targets P2 slot 1 specifically
        val choices =
            TurnChoices(
                mapOf(
                    Slot.p1(0) to TurnChoice.UseMove(tackle, targetSlot = Slot.p2(1)),
                    Slot.p1(1) to TurnChoice.UseMove(tackle),
                    Slot.p2(0) to TurnChoice.UseMove(tackle),
                    Slot.p2(1) to TurnChoice.UseMove(tackle),
                ),
            )

        val phase = MoveExecutionPhase(GenVRegistries, roll = fixedRoll, chanceCheck = noChance)
        val events = phase.resolve(PipelineState(battleState), choices).events

        // The fastest Pokemon (P1 slot 0) should deal damage to P2 slot 1
        val firstDamage = events.filterIsInstance<DamageDealt>().first()
        assertEquals(Slot.p2(1), firstDamage.target, "Should target the chosen slot")
    }

    // --- Spread moves ---

    @Test
    fun `ALL_OPPONENTS move hits both opposing slots`() {
        val battleState =
            BattleState.doubles(
                state(pokemon(fast)),
                state(pokemon(slow)),
                state(pokemon(midFast)),
                state(pokemon(midSlow)),
            )
        val choices =
            TurnChoices(
                mapOf(
                    Slot.p1(0) to TurnChoice.UseMove(hyperVoice),
                    Slot.p1(1) to TurnChoice.UseMove(tackle),
                    Slot.p2(0) to TurnChoice.UseMove(tackle),
                    Slot.p2(1) to TurnChoice.UseMove(tackle),
                ),
            )

        val phase = MoveExecutionPhase(GenVRegistries, roll = fixedRoll, chanceCheck = noChance)
        val events = phase.resolve(PipelineState(battleState), choices).events

        // P1 slot 0 (fastest) uses Hyper Voice — should hit both P2 slots
        // Find damage events right after the first MoveAttempted
        val firstAttempt = events.indexOfFirst { it is MoveAttempted && (it as MoveAttempted).attacker == Slot.p1(0) }
        val damageAfterFirst = events.drop(firstAttempt + 1).takeWhile { it is DamageDealt || it is PokemonFainted }
        val damageEvents = damageAfterFirst.filterIsInstance<DamageDealt>()

        assertEquals(2, damageEvents.size, "Hyper Voice should hit 2 targets")
        assertEquals(Slot.p2(0), damageEvents[0].target)
        assertEquals(Slot.p2(1), damageEvents[1].target)
    }

    @Test
    fun `ALL_OTHER move hits ally and both opponents`() {
        val battleState =
            BattleState.doubles(
                state(pokemon(fast)),
                state(pokemon(slow)),
                state(pokemon(midFast)),
                state(pokemon(midSlow)),
            )
        val choices =
            TurnChoices(
                mapOf(
                    Slot.p1(0) to TurnChoice.UseMove(earthquake),
                    Slot.p1(1) to TurnChoice.UseMove(tackle),
                    Slot.p2(0) to TurnChoice.UseMove(tackle),
                    Slot.p2(1) to TurnChoice.UseMove(tackle),
                ),
            )

        val phase = MoveExecutionPhase(GenVRegistries, roll = fixedRoll, chanceCheck = noChance)
        val events = phase.resolve(PipelineState(battleState), choices).events

        // P1 slot 0 (fastest) uses Earthquake — should hit P1 slot 1, P2 slot 0, P2 slot 1
        val firstAttempt = events.indexOfFirst { it is MoveAttempted && (it as MoveAttempted).attacker == Slot.p1(0) }
        val damageAfterFirst = events.drop(firstAttempt + 1).takeWhile { it is DamageDealt || it is PokemonFainted }
        val damageEvents = damageAfterFirst.filterIsInstance<DamageDealt>()

        assertEquals(3, damageEvents.size, "Earthquake should hit 3 targets (ally + 2 opponents)")
        val targets = damageEvents.map { it.target }.toSet()
        assertTrue(Slot.p1(1) in targets, "Should hit ally")
        assertTrue(Slot.p2(0) in targets, "Should hit opponent 0")
        assertTrue(Slot.p2(1) in targets, "Should hit opponent 1")
    }

    @Test
    fun `spread moves deal 75 percent damage`() {
        val battleState =
            BattleState.doubles(
                state(pokemon(fast)),
                state(pokemon(slow)),
                state(pokemon(midFast)),
                state(pokemon(midSlow)),
            )
        val attacker = state(pokemon(fast))
        val defender = state(pokemon(midFast))

        val singleDamage = calculateDamage(attacker, defender, hyperVoice, roll = fixedRoll, spreadModifier = 1.0)
        val spreadDamage = calculateDamage(attacker, defender, hyperVoice, roll = fixedRoll, spreadModifier = 0.75)

        val ratio = spreadDamage.damage.toDouble() / singleDamage.damage.toDouble()
        assertTrue(ratio in 0.70..0.80, "Spread should deal ~75% of single-target damage, got $ratio")
    }

    // --- Faint during spread ---

    @Test
    fun `fainted target is skipped during spread move`() {
        // P2 slot 0 has 1 HP — will faint from Earthquake. P2 slot 1 should still take damage.
        val battleState =
            BattleState.doubles(
                state(pokemon(fast)),
                state(pokemon(slow)),
                PokemonState(pokemon(midFast), currentHp = 1),
                state(pokemon(midSlow)),
            )
        val choices =
            TurnChoices(
                mapOf(
                    Slot.p1(0) to TurnChoice.UseMove(earthquake),
                    Slot.p1(1) to TurnChoice.UseMove(tackle),
                    Slot.p2(0) to TurnChoice.UseMove(tackle),
                    Slot.p2(1) to TurnChoice.UseMove(tackle),
                ),
            )

        val phase = MoveExecutionPhase(GenVRegistries, roll = fixedRoll, chanceCheck = noChance)
        val events = phase.resolve(PipelineState(battleState), choices).events

        // P2 slot 0 should faint from Earthquake
        val faints = events.filterIsInstance<PokemonFainted>()
        assertTrue(faints.any { it.slot == Slot.p2(0) }, "P2 slot 0 should faint")

        // P2 slot 1 should still take damage
        val damageToSlot1 = events.filterIsInstance<DamageDealt>().filter { it.target == Slot.p2(1) }
        assertTrue(damageToSlot1.isNotEmpty(), "P2 slot 1 should still take damage")
    }

    // --- End-of-turn with 4 slots ---

    @Test
    fun `end-of-turn effects tick for all 4 slots`() {
        val battleState =
            BattleState.doubles(
                PokemonState(pokemon(fireType), currentHp = pokemon(fireType).maxHp),
                PokemonState(pokemon(groundType), currentHp = pokemon(groundType).maxHp, status = StatusCondition.BURN),
                PokemonState(pokemon(fireType), currentHp = pokemon(fireType).maxHp),
                PokemonState(pokemon(groundType), currentHp = pokemon(groundType).maxHp, item = Item.LEFTOVERS),
                field = FieldState(weather = Weather.SANDSTORM, weatherTurnsRemaining = 3),
            )
        val choices =
            TurnChoices(
                mapOf(
                    Slot.p1(0) to TurnChoice.UseMove(tackle),
                    Slot.p1(1) to TurnChoice.UseMove(tackle),
                    Slot.p2(0) to TurnChoice.UseMove(tackle),
                    Slot.p2(1) to TurnChoice.UseMove(tackle),
                ),
            )

        val events = EndOfTurnPhase(GenVRegistries).resolve(PipelineState(battleState), choices).events

        // Weather damage: Fire types take sandstorm, Ground types immune
        val weatherDmg = events.filterIsInstance<WeatherDamage>()
        assertEquals(2, weatherDmg.size, "Both Fire types should take sandstorm damage")

        // Status damage: one Pokemon has burn
        val statusDmg = events.filterIsInstance<StatusDamage>()
        assertEquals(1, statusDmg.size)
        assertEquals(Slot.p1(1), statusDmg[0].target, "Ground type on side 1 has burn")

        // Item healing: one Pokemon has Leftovers (but at full HP, so no healing)
        val healing = events.filterIsInstance<ItemHealing>()
        assertEquals(0, healing.size, "Leftovers doesn't heal at full HP")

        // Weather tick
        val tick = events.filterIsInstance<WeatherTick>()
        assertEquals(1, tick.size)
        assertEquals(2, tick[0].turnsRemaining)
    }

    // --- Priority in doubles ---

    @Test
    fun `priority move goes first regardless of speed in doubles`() {
        val battleState =
            BattleState.doubles(
                state(pokemon(slow)),
                state(pokemon(fast)),
                state(pokemon(midFast)),
                state(pokemon(midSlow)),
            )
        val choices =
            TurnChoices(
                mapOf(
                    // Slow Pokemon with +1 priority Mach Punch
                    Slot.p1(0) to TurnChoice.UseMove(machPunch),
                    Slot.p1(1) to TurnChoice.UseMove(tackle),
                    Slot.p2(0) to TurnChoice.UseMove(tackle),
                    Slot.p2(1) to TurnChoice.UseMove(tackle),
                ),
            )

        val order = resolveMoveOrder(battleState, choices)

        // Slow Pokemon with Mach Punch should go first
        assertEquals(Slot.p1(0), order.order[0], "Priority +1 should go first")
        assertEquals(OrderReason.PRIORITY, order.leadReason)
    }
}
