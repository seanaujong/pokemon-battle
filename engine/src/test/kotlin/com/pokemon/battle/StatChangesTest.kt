package com.pokemon.battle

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.MoveAttempted
import com.pokemon.battle.engine.PipelineState
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.MoveEffect
import com.pokemon.battle.model.MoveTarget
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Species
import com.pokemon.battle.model.StatStages
import com.pokemon.battle.model.StatType
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.calcMaxHp
import com.pokemon.battle.model.stageMultiplier
import com.pokemon.battle.phase.MoveExecutionPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatChangesTest {
    private val species =
        Species(
            name = "Balanced",
            types = listOf(Type.NORMAL),
            baseHp = 80,
            baseAttack = 100,
            baseDefense = 100,
            baseSpecialAttack = 100,
            baseSpecialDefense = 100,
            baseSpeed = 100,
        )

    private val pokemon = Pokemon(species, level = 50)
    private val maxHp = calcMaxHp(species.baseHp, 50)

    private val tackle =
        Move(
            name = "Tackle",
            type = Type.NORMAL,
            category = MoveCategory.PHYSICAL,
            power = 40,
        )

    private val swordsDance =
        Move(
            name = "Swords Dance",
            type = Type.NORMAL,
            category = MoveCategory.STATUS,
            power = 0,
            target = MoveTarget.SELF,
            effects = listOf(MoveEffect.StatBoost(StatType.ATTACK, 2)),
        )

    private val growl =
        Move(
            name = "Growl",
            type = Type.NORMAL,
            category = MoveCategory.STATUS,
            power = 0,
            target = MoveTarget.ONE_OPPONENT,
            effects = listOf(MoveEffect.StatBoost(StatType.ATTACK, -1)),
        )

    private fun makeState(): BattleState {
        val p1 = PokemonState(pokemon, currentHp = maxHp)
        val p2 = PokemonState(pokemon, currentHp = maxHp)
        return BattleState.singles(p1, p2)
    }

    @Test
    fun `Swords Dance raises user attack by 2 stages`() {
        val state = makeState()
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(swordsDance),
                TurnChoice.UseMove(tackle),
            )
        val phase = MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false })
        val events = phase.resolve(PipelineState(state), choices).events

        val statChanged = events.filterIsInstance<StatChanged>()
        assertEquals(1, statChanged.size)
        assertEquals(Slot.p1(), statChanged[0].target)
        assertEquals(StatType.ATTACK, statChanged[0].stat)
        assertEquals(2, statChanged[0].stages)

        val p1Attempts = events.filterIsInstance<MoveAttempted>().filter { it.attacker == Slot.p1() }
        assertEquals(1, p1Attempts.size)
        assertEquals("Swords Dance", p1Attempts[0].move.name)
    }

    @Test
    fun `Swords Dance boosts damage on the following turn`() {
        val state = makeState()
        val fixedRoll: (IntRange) -> Int = { 100 }
        val phase = MoveExecutionPhase(roll = fixedRoll, chanceCheck = { _, _ -> false })

        val turn1Choices =
            TurnChoices.singles(
                TurnChoice.UseMove(swordsDance),
                TurnChoice.UseMove(tackle),
            )
        val turn1Events = phase.resolve(PipelineState(state), turn1Choices).events
        val stateAfterTurn1 = turn1Events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }

        assertEquals(2, stateAfterTurn1.pokemonFor(Slot.p1()).statStages.attack)

        val turn2Choices =
            TurnChoices.singles(
                TurnChoice.UseMove(tackle),
                TurnChoice.UseMove(tackle),
            )
        val turn2Events = phase.resolve(PipelineState(stateAfterTurn1), turn2Choices).events

        val damageEvents = turn2Events.filterIsInstance<DamageDealt>()
        val p1Damage = damageEvents.first { it.target == Slot.p2() }.amount
        val p2Damage = damageEvents.first { it.target == Slot.p1() }.amount

        assertTrue(p1Damage > p2Damage)
        val ratio = p1Damage.toDouble() / p2Damage.toDouble()
        assertTrue(ratio in 1.8..2.2, "Damage ratio should be ~2.0, got $ratio")
    }

    @Test
    fun `stat stage multiplier at +2 is 2x`() {
        assertEquals(2.0, stageMultiplier(2))
    }

    @Test
    fun `Growl lowers opponent attack by 1 stage`() {
        val state = makeState()
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(growl),
                TurnChoice.UseMove(tackle),
            )
        val phase = MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false })
        val events = phase.resolve(PipelineState(state), choices).events

        val statChanged = events.filterIsInstance<StatChanged>()
        assertEquals(1, statChanged.size)
        assertEquals(Slot.p2(), statChanged[0].target)
        assertEquals(-1, statChanged[0].stages)
    }

    @Test
    fun `Growl reduces opponent damage`() {
        val state = makeState()
        val fixedRoll: (IntRange) -> Int = { 100 }
        val phase = MoveExecutionPhase(roll = fixedRoll, chanceCheck = { _, _ -> false })

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(growl),
                TurnChoice.UseMove(tackle),
            )
        val turn1Events = phase.resolve(PipelineState(state), choices).events
        val stateAfterGrowl = turn1Events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }

        assertEquals(-1, stateAfterGrowl.pokemonFor(Slot.p2()).statStages.attack)

        val turn2Choices =
            TurnChoices.singles(
                TurnChoice.UseMove(tackle),
                TurnChoice.UseMove(tackle),
            )
        val turn2Events = phase.resolve(PipelineState(stateAfterGrowl), turn2Choices).events

        val damageEvents = turn2Events.filterIsInstance<DamageDealt>()
        val p1Damage = damageEvents.first { it.target == Slot.p2() }.amount
        val p2Damage = damageEvents.first { it.target == Slot.p1() }.amount

        assertTrue(p2Damage < p1Damage)
    }

    @Test
    fun `stat stages clamp at +6`() {
        val stages = StatStages(attack = 5)
        assertEquals(6, stages.withChange(StatType.ATTACK, 3).attack)
    }

    @Test
    fun `stat stages clamp at -6`() {
        val stages = StatStages(attack = -5)
        assertEquals(-6, stages.withChange(StatType.ATTACK, -3).attack)
    }

    @Test
    fun `StatChanged event applies clamping correctly`() {
        val state = makeState()
        val event1 = StatChanged(Slot.p1(), StatType.ATTACK, 6)
        val state2 = event1.apply(state)
        assertEquals(6, state2.pokemonFor(Slot.p1()).statStages.attack)

        val event2 = StatChanged(Slot.p1(), StatType.ATTACK, 2)
        val state3 = event2.apply(state2)
        assertEquals(6, state3.pokemonFor(Slot.p1()).statStages.attack)
    }
}
