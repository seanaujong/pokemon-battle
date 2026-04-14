package com.pokemon.battle

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.GenVSpeedResolver
import com.pokemon.battle.engine.MoveAttempted
import com.pokemon.battle.engine.MoveFailed
import com.pokemon.battle.engine.MoveOrderDecided
import com.pokemon.battle.engine.PipelineState
import com.pokemon.battle.engine.StatusCleared
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.VolatileAdded
import com.pokemon.battle.engine.VolatileRemoved
import com.pokemon.battle.engine.resolveMoveOrder
import com.pokemon.battle.model.FailReason
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.OrderReason
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Species
import com.pokemon.battle.model.StatusCondition
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.Volatile
import com.pokemon.battle.model.calcMaxHp
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StatusMechanicsTest {
    private val fastSpecies =
        Species(
            name = "Fast",
            types = listOf(Type.NORMAL),
            baseHp = 80,
            baseAttack = 100,
            baseDefense = 80,
            baseSpecialAttack = 80,
            baseSpecialDefense = 80,
            baseSpeed = 100,
        )

    private val slowSpecies =
        Species(
            name = "Slow",
            types = listOf(Type.NORMAL),
            baseHp = 80,
            baseAttack = 100,
            baseDefense = 80,
            baseSpecialAttack = 80,
            baseSpecialDefense = 80,
            baseSpeed = 50,
        )

    private val tackle =
        Move(
            name = "Tackle",
            type = Type.NORMAL,
            category = MoveCategory.PHYSICAL,
            power = 40,
        )

    @Suppress("LongParameterList") // Test helper — all params have defaults
    private fun makeState(
        p1Status: StatusCondition? = null,
        p1Volatiles: Set<Volatile> = emptySet(),
        p2Status: StatusCondition? = null,
        p2Volatiles: Set<Volatile> = emptySet(),
        p1Species: Species = fastSpecies,
        p2Species: Species = slowSpecies,
    ): BattleState {
        val p1 =
            PokemonState(
                Pokemon(p1Species, 50),
                currentHp = calcMaxHp(p1Species.baseHp, 50),
                status = p1Status,
                volatiles = p1Volatiles,
            )
        val p2 =
            PokemonState(
                Pokemon(p2Species, 50),
                currentHp = calcMaxHp(p2Species.baseHp, 50),
                status = p2Status,
                volatiles = p2Volatiles,
            )
        return BattleState.singles(p1, p2)
    }

    private val bothTackle =
        TurnChoices.singles(
            TurnChoice.UseMove(tackle),
            TurnChoice.UseMove(tackle),
        )

    // --- Paralysis ---

    @Test
    fun `paralysis halves effective speed`() {
        val normal = PokemonState(Pokemon(fastSpecies, 50), currentHp = 100)
        val paralyzed = normal.copy(status = StatusCondition.PARALYSIS)
        assertEquals(
            GenVSpeedResolver.effectiveSpeed(normal, Slot.p1(), BattleState.singles(normal, normal)) * 0.5,
            GenVSpeedResolver.effectiveSpeed(paralyzed, Slot.p1(), BattleState.singles(paralyzed, normal)),
        )
    }

    @Test
    fun `paralysis can reverse speed ordering`() {
        val state = makeState(p1Status = StatusCondition.PARALYSIS)
        val order = resolveMoveOrder(state, bothTackle)
        assertEquals(Slot.p2(), order.order.first())
        assertEquals(OrderReason.SPEED, order.leadReason)
    }

    @Test
    fun `fully paralyzed Pokemon cannot act`() {
        val state = makeState(p1Status = StatusCondition.PARALYSIS)
        val phase = MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> true })
        val events = phase.resolve(PipelineState(state), bothTackle).events

        val moveFailed = events.filterIsInstance<MoveFailed>()
        assertEquals(1, moveFailed.size)
        assertEquals(Slot.p1(), moveFailed[0].attacker)
        assertEquals(FailReason.FULLY_PARALYZED, moveFailed[0].reason)
    }

    @Test
    fun `paralyzed Pokemon acts when chance check fails`() {
        val state = makeState(p1Status = StatusCondition.PARALYSIS)
        val phase = MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false })
        val events = phase.resolve(PipelineState(state), bothTackle).events

        assertEquals(0, events.filterIsInstance<MoveFailed>().size)
        assertEquals(2, events.filterIsInstance<MoveAttempted>().size)
    }

    // --- Sleep ---

    @Test
    fun `sleeping Pokemon cannot act and counter decrements`() {
        val state =
            makeState(
                p1Status = StatusCondition.SLEEP,
                p1Volatiles = setOf(Volatile.Sleep(turnsRemaining = 2)),
            )
        val phase = MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false })
        val events = phase.resolve(PipelineState(state), bothTackle).events

        val removed = events.filterIsInstance<VolatileRemoved>()
        val added = events.filterIsInstance<VolatileAdded>()
        assertEquals(1, removed.size)
        assertEquals(Volatile.Sleep(2), removed[0].volatile)
        assertEquals(1, added.size)
        assertEquals(Volatile.Sleep(1), added[0].volatile)

        val moveFailed = events.filterIsInstance<MoveFailed>()
        assertEquals(1, moveFailed.size)
        assertEquals(FailReason.ASLEEP, moveFailed[0].reason)
    }

    @Test
    fun `Pokemon wakes up when sleep counter reaches zero`() {
        val state =
            makeState(
                p1Status = StatusCondition.SLEEP,
                p1Volatiles = setOf(Volatile.Sleep(turnsRemaining = 1)),
            )
        val phase = MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false })
        val events = phase.resolve(PipelineState(state), bothTackle).events

        val cleared = events.filterIsInstance<StatusCleared>()
        assertEquals(1, cleared.size)
        assertEquals(Slot.p1(), cleared[0].target)

        assertTrue(events.filterIsInstance<MoveAttempted>().any { it.attacker == Slot.p1() })
        assertEquals(0, events.filterIsInstance<MoveFailed>().size)
    }

    @Test
    fun `sleep lasts exactly N turns then wakes`() {
        var state =
            makeState(
                p1Status = StatusCondition.SLEEP,
                p1Volatiles = setOf(Volatile.Sleep(turnsRemaining = 2)),
            )
        val phase = MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false })

        // Turn 1: P1 sleeps
        val turn1Events = phase.resolve(PipelineState(state), bothTackle).events
        assertEquals(FailReason.ASLEEP, turn1Events.filterIsInstance<MoveFailed>()[0].reason)
        state = turn1Events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }

        // Turn 2: P1 wakes and acts
        val turn2Events = phase.resolve(PipelineState(state), bothTackle).events
        assertEquals(StatusCondition.SLEEP, turn2Events.filterIsInstance<StatusCleared>()[0].status)
        assertTrue(turn2Events.filterIsInstance<MoveAttempted>().any { it.attacker == Slot.p1() })
    }

    // --- Freeze ---

    @Test
    fun `frozen Pokemon cannot act when thaw fails`() {
        val state = makeState(p1Status = StatusCondition.FREEZE)
        val phase = MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false })
        val events = phase.resolve(PipelineState(state), bothTackle).events

        val moveFailed = events.filterIsInstance<MoveFailed>()
        assertEquals(1, moveFailed.size)
        assertEquals(FailReason.FROZEN, moveFailed[0].reason)
        assertEquals(Slot.p1(), moveFailed[0].attacker)
    }

    @Test
    fun `frozen Pokemon thaws and acts when thaw succeeds`() {
        val state = makeState(p1Status = StatusCondition.FREEZE)
        val phase = MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> true })
        val events = phase.resolve(PipelineState(state), bothTackle).events

        assertEquals(StatusCondition.FREEZE, events.filterIsInstance<StatusCleared>()[0].status)
        assertTrue(events.filterIsInstance<MoveAttempted>().any { it.attacker == Slot.p1() })
        assertEquals(0, events.filterIsInstance<MoveFailed>().size)
    }

    // --- Integration ---

    @Test
    fun `paralyzed fast Pokemon goes after slower sleeping Pokemon`() {
        val state =
            makeState(
                p1Status = StatusCondition.PARALYSIS,
                p2Status = StatusCondition.SLEEP,
                p2Volatiles = setOf(Volatile.Sleep(turnsRemaining = 2)),
            )
        val phase = MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false })
        val pipeline = TurnPipeline(listOf(MoveOrderPhase(), phase, EndOfTurnPhase()))
        val result = pipeline.resolveToCompletion(state, bothTackle)
        val events = result.events

        val order = assertIs<MoveOrderDecided>(events[0])
        assertEquals(Slot.p2(), order.order.first())
        assertEquals(OrderReason.SPEED, order.leadReason)

        val removed = assertIs<VolatileRemoved>(events[1])
        assertEquals(Slot.p2(), removed.target)
        val added = assertIs<VolatileAdded>(events[2])
        assertEquals(Slot.p2(), added.target)
        val failed = assertIs<MoveFailed>(events[3])
        assertEquals(Slot.p2(), failed.attacker)
        assertEquals(FailReason.ASLEEP, failed.reason)

        val attempt = assertIs<MoveAttempted>(events[4])
        assertEquals(Slot.p1(), attempt.attacker)

        val damage = assertIs<DamageDealt>(events[5])
        assertEquals(Slot.p2(), damage.target)
    }
}
