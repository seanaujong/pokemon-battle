package com.pokemon.battle

import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*
import com.pokemon.battle.phase.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StatChangesTest {

    private val species = Species(
        name = "Balanced", types = listOf(Type.NORMAL),
        baseHp = 80, baseAttack = 100, baseDefense = 100,
        baseSpecialAttack = 100, baseSpecialDefense = 100, baseSpeed = 100
    )

    private val pokemon = Pokemon(species, level = 50)
    private val maxHp = calcMaxHp(species.baseHp, 50)

    private val tackle = Move(
        name = "Tackle", type = Type.NORMAL,
        category = MoveCategory.PHYSICAL, power = 40
    )

    private val swordsDance = Move(
        name = "Swords Dance", type = Type.NORMAL,
        category = MoveCategory.STATUS, power = 0,
        target = MoveTarget.SELF,
        effects = listOf(MoveEffect.StatBoost(StatType.ATTACK, 2))
    )

    private val growl = Move(
        name = "Growl", type = Type.NORMAL,
        category = MoveCategory.STATUS, power = 0,
        target = MoveTarget.OPPONENT,
        effects = listOf(MoveEffect.StatBoost(StatType.ATTACK, -1))
    )

    private fun makeState(): BattleState {
        val p1 = PokemonState(pokemon, currentHp = maxHp)
        val p2 = PokemonState(pokemon, currentHp = maxHp)
        return BattleState(p1, p2)
    }

    // --- Swords Dance ---

    @Test
    fun `Swords Dance raises user attack by 2 stages`() {
        val state = makeState()
        val choices = TurnChoices(
            p1 = TurnChoice.UseMove(swordsDance),
            p2 = TurnChoice.UseMove(tackle)
        )
        val phase = MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false })
        val events = phase.resolve(state, choices)

        val statChanged = events.filterIsInstance<StatChanged>()
        assertEquals(1, statChanged.size)
        assertEquals(Player.P1, statChanged[0].target)
        assertEquals(StatType.ATTACK, statChanged[0].stat)
        assertEquals(2, statChanged[0].stages)

        // No damage from Swords Dance
        val damageFromP1 = events.filterIsInstance<DamageDealt>().filter {
            // Damage where P1 is the attacker (target is P2)
            it.target == Player.P2
        }
        // P1 used Swords Dance, not a damaging move — P2 should have dealt damage to P1 though
        val p1Attempts = events.filterIsInstance<MoveAttempted>().filter { it.attacker == Player.P1 }
        assertEquals(1, p1Attempts.size)
        assertEquals("Swords Dance", p1Attempts[0].move.name)
    }

    @Test
    fun `Swords Dance boosts damage on the following turn`() {
        val state = makeState()
        val fixedRoll: (IntRange) -> Int = { 100 }
        val phase = MoveExecutionPhase(roll = fixedRoll, chanceCheck = { _, _ -> false })

        // Turn 1: P1 uses Swords Dance, P2 uses Tackle
        val turn1Choices = TurnChoices(
            p1 = TurnChoice.UseMove(swordsDance),
            p2 = TurnChoice.UseMove(tackle)
        )
        val turn1Events = phase.resolve(state, turn1Choices)
        val stateAfterTurn1 = turn1Events.fold(state) { s, e -> e.apply(s) }

        // P1 should now have +2 attack
        assertEquals(2, stateAfterTurn1.pokemon1.statStages.attack)

        // Turn 2: Both use Tackle — P1's damage should be boosted
        val turn2Choices = TurnChoices(
            p1 = TurnChoice.UseMove(tackle),
            p2 = TurnChoice.UseMove(tackle)
        )
        val turn2Events = phase.resolve(stateAfterTurn1, turn2Choices)

        val damageEvents = turn2Events.filterIsInstance<DamageDealt>()
        val p1Damage = damageEvents.first { it.target == Player.P2 }.amount
        val p2Damage = damageEvents.first { it.target == Player.P1 }.amount

        // +2 attack = 2.0x multiplier, so P1 should deal roughly double
        assertTrue(p1Damage > p2Damage, "P1 damage ($p1Damage) should exceed P2 damage ($p2Damage) after Swords Dance")
        val ratio = p1Damage.toDouble() / p2Damage.toDouble()
        assertTrue(ratio in 1.8..2.2, "Damage ratio should be ~2.0, got $ratio")
    }

    @Test
    fun `stat stage multiplier at +2 is 2x`() {
        assertEquals(2.0, stageMultiplier(2))
    }

    // --- Growl ---

    @Test
    fun `Growl lowers opponent attack by 1 stage`() {
        val state = makeState()
        val choices = TurnChoices(
            p1 = TurnChoice.UseMove(growl),
            p2 = TurnChoice.UseMove(tackle)
        )
        val phase = MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false })
        val events = phase.resolve(state, choices)

        val statChanged = events.filterIsInstance<StatChanged>()
        assertEquals(1, statChanged.size)
        assertEquals(Player.P2, statChanged[0].target)
        assertEquals(StatType.ATTACK, statChanged[0].stat)
        assertEquals(-1, statChanged[0].stages)
    }

    @Test
    fun `Growl reduces opponent damage`() {
        val state = makeState()
        val fixedRoll: (IntRange) -> Int = { 100 }
        val phase = MoveExecutionPhase(roll = fixedRoll, chanceCheck = { _, _ -> false })

        // Turn 1: P1 uses Growl, P2 uses Tackle (with lowered attack)
        val choices = TurnChoices(
            p1 = TurnChoice.UseMove(growl),
            p2 = TurnChoice.UseMove(tackle)
        )
        val turn1Events = phase.resolve(state, choices)
        val stateAfterGrowl = turn1Events.fold(state) { s, e -> e.apply(s) }

        assertEquals(-1, stateAfterGrowl.pokemon2.statStages.attack)

        // Turn 2: Both use Tackle — P2's damage should be reduced
        val turn2Choices = TurnChoices(
            p1 = TurnChoice.UseMove(tackle),
            p2 = TurnChoice.UseMove(tackle)
        )
        val turn2Events = phase.resolve(stateAfterGrowl, turn2Choices)

        val damageEvents = turn2Events.filterIsInstance<DamageDealt>()
        val p1Damage = damageEvents.first { it.target == Player.P2 }.amount
        val p2Damage = damageEvents.first { it.target == Player.P1 }.amount

        // -1 attack = 0.667x multiplier, so P2 should deal less
        assertTrue(p2Damage < p1Damage, "P2 damage ($p2Damage) should be less than P1 damage ($p1Damage) after Growl")
    }

    // --- Clamping ---

    @Test
    fun `stat stages clamp at +6`() {
        val stages = StatStages(attack = 5)
        val result = stages.withChange(StatType.ATTACK, 3) // 5 + 3 = 8, clamped to 6
        assertEquals(6, result.attack)
    }

    @Test
    fun `stat stages clamp at -6`() {
        val stages = StatStages(attack = -5)
        val result = stages.withChange(StatType.ATTACK, -3) // -5 + -3 = -8, clamped to -6
        assertEquals(-6, result.attack)
    }

    @Test
    fun `StatChanged event applies clamping correctly`() {
        val state = makeState()
        // Apply +6 then another +2 — should stay at 6
        val event1 = StatChanged(Player.P1, StatType.ATTACK, 6)
        val state2 = event1.apply(state)
        assertEquals(6, state2.pokemon1.statStages.attack)

        val event2 = StatChanged(Player.P1, StatType.ATTACK, 2)
        val state3 = event2.apply(state2)
        assertEquals(6, state3.pokemon1.statStages.attack)
    }
}
