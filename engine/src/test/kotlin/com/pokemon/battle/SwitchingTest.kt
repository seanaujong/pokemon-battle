package com.pokemon.battle

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.MoveAttempted
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.engine.SwitchIn
import com.pokemon.battle.engine.SwitchOut
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.VolatileRemoved
import com.pokemon.battle.engine.resolveMoveOrder
import com.pokemon.battle.model.Effectiveness
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Species
import com.pokemon.battle.model.StatStages
import com.pokemon.battle.model.StatusCondition
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.Volatile
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SwitchingTest {
    private val speciesA = Species("SpeciesA", listOf(Type.FIRE), 80, 100, 80, 80, 80, 100)
    private val speciesB = Species("SpeciesB", listOf(Type.WATER), 80, 100, 80, 80, 80, 80)
    private val speciesC = Species("SpeciesC", listOf(Type.GRASS), 80, 100, 80, 80, 80, 60)

    private fun pokemon(species: Species) = Pokemon(species, level = 50)

    private fun state(pokemon: Pokemon) = PokemonState(pokemon, currentHp = pokemon.maxHp)

    private val tackle = Move("Tackle", Type.NORMAL, MoveCategory.PHYSICAL, 40)

    private val fixedRoll: (IntRange) -> Int = { 100 }
    private val noChance: ChanceCheck = { _, _ -> false }

    // --- Voluntary switch ---

    @Test
    fun `voluntary switch replaces Pokemon in slot`() {
        val active1 = state(pokemon(speciesA))
        val active2 = state(pokemon(speciesB))
        val benched = state(pokemon(speciesC))

        val battleState = BattleState.singles(active1, active2, p1Bench = listOf(benched))
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(tackle),
            )

        val phase = SwitchPhase()
        val events = phase.resolve(battleState, choices).events

        // Should see SwitchOut, SwitchIn, then VolatileAdded(JustSwitchedIn)
        assertEquals(3, events.size)
        assertIs<SwitchOut>(events[0])
        assertEquals(Slot.p1(), (events[0] as SwitchOut).slot)

        assertIs<SwitchIn>(events[1])
        assertEquals(Slot.p1(), (events[1] as SwitchIn).slot)
        assertEquals(0, (events[1] as SwitchIn).benchIndex)

        // Apply events — slot should now have SpeciesC
        val newState = events.fold(battleState) { s, e -> e.apply(s) }
        assertEquals("SpeciesC", newState.pokemonFor(Slot.p1()).pokemon.species.name)

        // SpeciesA should be on the bench
        assertEquals(1, newState.benchFor(Side.SIDE_1).size)
        assertEquals("SpeciesA", newState.benchFor(Side.SIDE_1)[0].pokemon.species.name)
    }

    @Test
    fun `switch happens before moves in pipeline`() {
        val active1 = state(pokemon(speciesA))
        val active2 = state(pokemon(speciesB))
        val benched = state(pokemon(speciesC))

        val battleState = BattleState.singles(active1, active2, p1Bench = listOf(benched))
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(tackle),
            )

        val pipeline =
            TurnPipeline(
                listOf(
                    MoveOrderPhase(),
                    SwitchPhase(),
                    MoveExecutionPhase(roll = fixedRoll, chanceCheck = noChance),
                    EndOfTurnPhase(),
                ),
            )

        val result = pipeline.resolveToCompletion(battleState, choices)
        val events = result.events

        // Order: MoveOrderDecided, SwitchOut, SwitchIn, MoveAttempted, DamageDealt
        val switchOut = events.filterIsInstance<SwitchOut>()
        val moveAttempted = events.filterIsInstance<MoveAttempted>()
        assertEquals(1, switchOut.size)
        assertEquals(1, moveAttempted.size)

        val switchOutIndex = events.indexOf(switchOut[0])
        val moveAttemptedIndex = events.indexOf(moveAttempted[0])
        assertTrue(switchOutIndex < moveAttemptedIndex, "Switch should happen before move")

        // Damage should be dealt to the NEW Pokemon (SpeciesC), not the old one (SpeciesA)
        val damage = events.filterIsInstance<DamageDealt>()
        assertEquals(Slot.p1(), damage[0].target)
        // SpeciesC (Grass) takes normal damage from Normal-type Tackle
        assertEquals(Effectiveness.NEUTRAL, damage[0].effectiveness)
    }

    // --- Volatile clearing ---

    @Test
    fun `switch clears volatiles and stat stages`() {
        val boosted =
            PokemonState(
                pokemon(speciesA),
                currentHp = pokemon(speciesA).maxHp,
                statStages = StatStages(attack = 2),
                volatiles = setOf(Volatile.Confusion(3)),
            )
        val active2 = state(pokemon(speciesB))
        val benched = state(pokemon(speciesC))

        val battleState = BattleState.singles(boosted, active2, p1Bench = listOf(benched))
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(tackle),
            )

        val phase = SwitchPhase()
        val events = phase.resolve(battleState, choices).events
        val newState = events.fold(battleState) { s, e -> e.apply(s) }

        // The Pokemon on bench should have cleared volatiles and stat stages
        val benchedPokemon = newState.benchFor(Side.SIDE_1).last()
        assertEquals(StatStages(), benchedPokemon.statStages, "Stat stages should be cleared")
        assertEquals(emptySet(), benchedPokemon.volatiles, "Volatiles should be cleared")

        // Clearing should appear as events in the log
        assertTrue(events.any { it is VolatileRemoved }, "Volatile clearing should be logged")
        assertTrue(events.any { it is StatChanged }, "Stat clearing should be logged")
    }

    @Test
    fun `switch preserves status condition`() {
        val burned =
            PokemonState(
                pokemon(speciesA),
                currentHp = pokemon(speciesA).maxHp,
                status = StatusCondition.BURN,
            )
        val active2 = state(pokemon(speciesB))
        val benched = state(pokemon(speciesC))

        val battleState = BattleState.singles(burned, active2, p1Bench = listOf(benched))
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(tackle),
            )

        val phase = SwitchPhase()
        val events = phase.resolve(battleState, choices).events
        val newState = events.fold(battleState) { s, e -> e.apply(s) }

        // Status should persist on the benched Pokemon
        val benchedPokemon = newState.benchFor(Side.SIDE_1).last()
        assertEquals(StatusCondition.BURN, benchedPokemon.status, "Status should persist through switch")
    }

    // --- Faint replacement ---

    @Test
    fun `faint replacement brings bench Pokemon into slot`() {
        val fainted = PokemonState(pokemon(speciesA), currentHp = 0)
        val active2 = state(pokemon(speciesB))
        val benched = state(pokemon(speciesC))

        val battleState = BattleState.singles(fainted, active2, p1Bench = listOf(benched))

        // No SwitchOut for faint — just SwitchIn
        val switchIn = SwitchIn(Slot.p1(), benchIndex = 0)
        val newState = switchIn.apply(battleState)

        assertEquals("SpeciesC", newState.pokemonFor(Slot.p1()).pokemon.species.name)
        assertEquals(0, newState.benchFor(Side.SIDE_1).size, "Bench should be empty after replacement")
    }

    // --- Doubles switching ---

    @Test
    fun `one slot switches while the other attacks in doubles`() {
        val battleState =
            BattleState.doubles(
                state(pokemon(speciesA)),
                state(pokemon(speciesB)),
                state(pokemon(speciesA)),
                state(pokemon(speciesB)),
                p1Bench = listOf(state(pokemon(speciesC))),
            )

        val choices =
            TurnChoices(
                mapOf(
                    Slot.p1(0) to TurnChoice.Switch(benchIndex = 0),
                    Slot.p1(1) to TurnChoice.UseMove(tackle),
                    Slot.p2(0) to TurnChoice.UseMove(tackle),
                    Slot.p2(1) to TurnChoice.UseMove(tackle),
                ),
            )

        val pipeline =
            TurnPipeline(
                listOf(
                    MoveOrderPhase(),
                    SwitchPhase(),
                    MoveExecutionPhase(roll = fixedRoll, chanceCheck = noChance),
                    EndOfTurnPhase(),
                ),
            )

        val result = pipeline.resolveToCompletion(battleState, choices)
        val events = result.events

        // Switch happens
        assertTrue(events.any { it is SwitchOut && it.slot == Slot.p1(0) })
        assertTrue(events.any { it is SwitchIn && it.slot == Slot.p1(0) })

        // 3 moves execute (P1 slot 1, P2 slot 0, P2 slot 1 — P1 slot 0 switched)
        val attempts = events.filterIsInstance<MoveAttempted>()
        assertEquals(3, attempts.size, "3 Pokemon should use moves (1 switched)")
    }

    // --- Move ordering excludes switching slots ---

    @Test
    fun `switching slot is excluded from move ordering`() {
        val battleState =
            BattleState.singles(
                state(pokemon(speciesA)),
                state(pokemon(speciesB)),
                p1Bench = listOf(state(pokemon(speciesC))),
            )

        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(tackle),
            )

        val order = resolveMoveOrder(battleState, choices)

        // Only P2 should be in the move order (P1 is switching)
        assertEquals(1, order.order.size)
        assertEquals(Slot.p2(), order.order[0])
    }

    // --- Win condition ---

    @Test
    fun `side is defeated when all active fainted and bench empty`() {
        val fainted = PokemonState(pokemon(speciesA), currentHp = 0)
        val active = state(pokemon(speciesB))

        val state = BattleState.singles(fainted, active)
        assertTrue(state.isDefeated(Side.SIDE_1), "Side 1 should be defeated")
        assertEquals(false, state.isDefeated(Side.SIDE_2), "Side 2 should not be defeated")
    }

    @Test
    fun `side is not defeated if bench has Pokemon`() {
        val fainted = PokemonState(pokemon(speciesA), currentHp = 0)
        val active = state(pokemon(speciesB))
        val benched = state(pokemon(speciesC))

        val state = BattleState.singles(fainted, active, p1Bench = listOf(benched))
        assertEquals(false, state.isDefeated(Side.SIDE_1), "Side 1 has bench Pokemon")
    }
}
