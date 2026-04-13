package com.pokemon.battle

import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*
import com.pokemon.battle.phase.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StatusMechanicsTest {

    private val fastSpecies = Species(
        name = "Fast", types = listOf(Type.NORMAL),
        baseHp = 80, baseAttack = 100, baseDefense = 80,
        baseSpecialAttack = 80, baseSpecialDefense = 80, baseSpeed = 100
    )

    private val slowSpecies = Species(
        name = "Slow", types = listOf(Type.NORMAL),
        baseHp = 80, baseAttack = 100, baseDefense = 80,
        baseSpecialAttack = 80, baseSpecialDefense = 80, baseSpeed = 50
    )

    private val tackle = Move(
        name = "Tackle", type = Type.NORMAL,
        category = MoveCategory.PHYSICAL, power = 40
    )

    private fun makeState(
        p1Status: StatusCondition? = null,
        p1Volatiles: Set<Volatile> = emptySet(),
        p2Status: StatusCondition? = null,
        p2Volatiles: Set<Volatile> = emptySet(),
        p1Species: Species = fastSpecies,
        p2Species: Species = slowSpecies
    ): BattleState {
        val p1 = PokemonState(
            Pokemon(p1Species, 50),
            currentHp = calcMaxHp(p1Species.baseHp, 50),
            status = p1Status,
            volatiles = p1Volatiles
        )
        val p2 = PokemonState(
            Pokemon(p2Species, 50),
            currentHp = calcMaxHp(p2Species.baseHp, 50),
            status = p2Status,
            volatiles = p2Volatiles
        )
        return BattleState(p1, p2)
    }

    private val bothTackle = TurnChoices(
        p1 = TurnChoice.UseMove(tackle),
        p2 = TurnChoice.UseMove(tackle)
    )

    // --- Paralysis ---

    @Test
    fun `paralysis halves effective speed`() {
        val normal = PokemonState(Pokemon(fastSpecies, 50), currentHp = 100)
        val paralyzed = normal.copy(status = StatusCondition.PARALYSIS)

        val normalSpeed = normal.effectiveSpeed()
        val paralyzedSpeed = paralyzed.effectiveSpeed()

        assertEquals(normalSpeed * 0.5, paralyzedSpeed)
    }

    @Test
    fun `paralysis can reverse speed ordering`() {
        // P1 is faster (speed 100) but paralyzed → effective speed 50
        // P2 is slower (speed 50) → effective speed 55
        // P2 should go first
        val state = makeState(p1Status = StatusCondition.PARALYSIS)
        val order = resolveMoveOrder(state, bothTackle)

        assertEquals(Player.P2, order.first)
        assertEquals("speed", order.reason)
    }

    @Test
    fun `fully paralyzed Pokemon cannot act`() {
        val state = makeState(p1Status = StatusCondition.PARALYSIS)
        val phase = MoveExecutionPhase(
            roll = { 100 },
            chanceCheck = { _, _ -> true } // all chance checks succeed → paralysis triggers
        )

        val events = phase.resolve(state, bothTackle)

        // P2 goes first (P1 is paralyzed, slower), acts normally
        // P1 is fully paralyzed
        val moveFailed = events.filterIsInstance<MoveFailed>()
        assertEquals(1, moveFailed.size)
        assertEquals(Player.P1, moveFailed[0].attacker)
        assertEquals(FailReason.FULLY_PARALYZED, moveFailed[0].reason)
    }

    @Test
    fun `paralyzed Pokemon acts when chance check fails`() {
        val state = makeState(p1Status = StatusCondition.PARALYSIS)
        val phase = MoveExecutionPhase(
            roll = { 100 },
            chanceCheck = { _, _ -> false } // all chance checks fail → paralysis doesn't trigger
        )

        val events = phase.resolve(state, bothTackle)

        // Both should act — no MoveFailed
        val moveFailed = events.filterIsInstance<MoveFailed>()
        assertEquals(0, moveFailed.size)

        val attempts = events.filterIsInstance<MoveAttempted>()
        assertEquals(2, attempts.size)
    }

    // --- Sleep ---

    @Test
    fun `sleeping Pokemon cannot act and counter decrements`() {
        val state = makeState(
            p1Status = StatusCondition.SLEEP,
            p1Volatiles = setOf(Volatile.Sleep(turnsRemaining = 2))
        )
        val phase = MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false })

        val events = phase.resolve(state, bothTackle)

        // P1 is asleep — should see VolatileChanged (counter 2→1) and MoveFailed
        val volatileChanged = events.filterIsInstance<VolatileChanged>()
        assertEquals(1, volatileChanged.size)
        assertEquals(Volatile.Sleep(2), volatileChanged[0].old)
        assertEquals(Volatile.Sleep(1), volatileChanged[0].new)

        val moveFailed = events.filterIsInstance<MoveFailed>()
        assertEquals(1, moveFailed.size)
        assertEquals(FailReason.ASLEEP, moveFailed[0].reason)
    }

    @Test
    fun `Pokemon wakes up when sleep counter reaches zero`() {
        val state = makeState(
            p1Status = StatusCondition.SLEEP,
            p1Volatiles = setOf(Volatile.Sleep(turnsRemaining = 1))
        )
        val phase = MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false })

        val events = phase.resolve(state, bothTackle)

        // P1 wakes up — StatusCleared, then acts normally
        val cleared = events.filterIsInstance<StatusCleared>()
        assertEquals(1, cleared.size)
        assertEquals(StatusCondition.SLEEP, cleared[0].status)
        assertEquals(Player.P1, cleared[0].target)

        // P1 should have attempted a move after waking
        val attempts = events.filterIsInstance<MoveAttempted>()
        assertTrue(attempts.any { it.attacker == Player.P1 }, "P1 should act after waking")

        // No MoveFailed for P1
        val moveFailed = events.filterIsInstance<MoveFailed>()
        assertEquals(0, moveFailed.size)
    }

    @Test
    fun `sleep lasts exactly N turns then wakes`() {
        // Sleep(2): turn 1 = asleep (counter 2→1), turn 2 = asleep (counter 1→0... wait)
        // Actually Sleep(2): turn 1 decrement to 1, still > 0 → asleep.
        //                    turn 2 decrement to 0 → wake up and act.
        // So Sleep(2) means 1 full turn of sleep, then wake on turn 2.
        var state = makeState(
            p1Status = StatusCondition.SLEEP,
            p1Volatiles = setOf(Volatile.Sleep(turnsRemaining = 2))
        )
        val phase = MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false })

        // Turn 1: P1 sleeps
        val turn1Events = phase.resolve(state, bothTackle)
        val turn1Failed = turn1Events.filterIsInstance<MoveFailed>()
        assertEquals(1, turn1Failed.size)
        assertEquals(FailReason.ASLEEP, turn1Failed[0].reason)

        // Apply turn 1 events to get new state
        state = turn1Events.fold(state) { s, e -> e.apply(s) }

        // Turn 2: P1 wakes and acts
        val turn2Events = phase.resolve(state, bothTackle)
        val turn2Cleared = turn2Events.filterIsInstance<StatusCleared>()
        assertEquals(1, turn2Cleared.size)
        assertEquals(StatusCondition.SLEEP, turn2Cleared[0].status)

        val turn2Attempts = turn2Events.filterIsInstance<MoveAttempted>()
        assertTrue(turn2Attempts.any { it.attacker == Player.P1 })
    }

    // --- Freeze ---

    @Test
    fun `frozen Pokemon cannot act when thaw fails`() {
        val state = makeState(p1Status = StatusCondition.FREEZE)
        val phase = MoveExecutionPhase(
            roll = { 100 },
            chanceCheck = { _, _ -> false } // thaw fails
        )

        val events = phase.resolve(state, bothTackle)

        val moveFailed = events.filterIsInstance<MoveFailed>()
        assertEquals(1, moveFailed.size)
        assertEquals(FailReason.FROZEN, moveFailed[0].reason)
        assertEquals(Player.P1, moveFailed[0].attacker)
    }

    @Test
    fun `frozen Pokemon thaws and acts when thaw succeeds`() {
        val state = makeState(p1Status = StatusCondition.FREEZE)
        val phase = MoveExecutionPhase(
            roll = { 100 },
            chanceCheck = { _, _ -> true } // thaw succeeds (also means paralysis would trigger, but P1 isn't paralyzed)
        )

        val events = phase.resolve(state, bothTackle)

        // P1 thaws and acts
        val cleared = events.filterIsInstance<StatusCleared>()
        assertEquals(1, cleared.size)
        assertEquals(StatusCondition.FREEZE, cleared[0].status)

        val attempts = events.filterIsInstance<MoveAttempted>()
        assertTrue(attempts.any { it.attacker == Player.P1 })

        val moveFailed = events.filterIsInstance<MoveFailed>()
        assertEquals(0, moveFailed.size)
    }

    // --- Integration ---

    @Test
    fun `paralyzed fast Pokemon goes after slower healthy Pokemon`() {
        // P1: base speed 100, paralyzed → effective 50
        // P2: base speed 50, asleep with 2 turns → effective 55
        // P2 goes first but is asleep. P1 is paralyzed but chanceCheck = false so it acts.
        val state = makeState(
            p1Status = StatusCondition.PARALYSIS,
            p2Status = StatusCondition.SLEEP,
            p2Volatiles = setOf(Volatile.Sleep(turnsRemaining = 2))
        )
        val phase = MoveExecutionPhase(
            roll = { 100 },
            chanceCheck = { _, _ -> false } // paralysis doesn't skip, freeze doesn't thaw
        )
        val pipeline = TurnPipeline(listOf(MoveOrderPhase(), phase, EndOfTurnPhase()))
        val result = pipeline.resolve(state, bothTackle)
        val events = result.events

        // Event 1: P2 goes first (speed 55 > 50)
        val order = assertIs<MoveOrderDecided>(events[0])
        assertEquals(Player.P2, order.firstAttacker)
        assertEquals("speed", order.reason)

        // Event 2-3: P2 is asleep (volatile change + move failed)
        val volatileChanged = assertIs<VolatileChanged>(events[1])
        assertEquals(Player.P2, volatileChanged.target)
        val failed = assertIs<MoveFailed>(events[2])
        assertEquals(Player.P2, failed.attacker)
        assertEquals(FailReason.ASLEEP, failed.reason)

        // Event 4: P1 acts (paralysis didn't trigger)
        val attempt = assertIs<MoveAttempted>(events[3])
        assertEquals(Player.P1, attempt.attacker)

        // Event 5: Damage dealt to P2
        val damage = assertIs<DamageDealt>(events[4])
        assertEquals(Player.P2, damage.target)
    }
}
