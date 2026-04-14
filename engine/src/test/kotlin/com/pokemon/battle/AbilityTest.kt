package com.pokemon.battle

import com.pokemon.battle.engine.AbilityBlocked
import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.MoveAttempted
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.FieldState
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.MoveTarget
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Species
import com.pokemon.battle.model.StatType
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.Weather
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AbilityTest {
    private val normalSpecies = Species("Normal", listOf(Type.NORMAL), 80, 100, 80, 80, 80, 100)
    private val ghostSpecies = Species("Ghost", listOf(Type.GHOST, Type.POISON), 60, 65, 60, 130, 75, 110)
    private val groundSpecies = Species("Ground", listOf(Type.GROUND), 80, 100, 80, 80, 80, 60)

    private fun pokemon(species: Species) = Pokemon(species, level = 50)

    private fun state(
        pokemon: Pokemon,
        ability: Ability? = null,
    ) = PokemonState(pokemon, currentHp = pokemon.maxHp, ability = ability)

    private val tackle = Move("Tackle", Type.NORMAL, MoveCategory.PHYSICAL, 40)
    private val earthquake = Move("Earthquake", Type.GROUND, MoveCategory.PHYSICAL, 100, target = MoveTarget.ALL_OTHER)
    private val mudSlap = Move("Mud-Slap", Type.GROUND, MoveCategory.SPECIAL, 20)

    private val fixedRoll: (IntRange) -> Int = { 100 }
    private val noChance: ChanceCheck = { _, _ -> false }

    // --- Levitate ---

    @Test
    fun `Levitate blocks Ground-type moves`() {
        val battleState =
            BattleState.singles(
                state(pokemon(groundSpecies)),
                state(pokemon(ghostSpecies), ability = Ability.LEVITATE),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(mudSlap),
                TurnChoice.UseMove(tackle),
            )

        val phase = MoveExecutionPhase(roll = fixedRoll, chanceCheck = noChance)
        val events = phase.resolve(battleState, choices)

        val blocked = events.filterIsInstance<AbilityBlocked>()
        assertEquals(1, blocked.size)
        assertEquals(Ability.LEVITATE, blocked[0].ability)
        assertEquals(Slot.p2(), blocked[0].slot)

        // No damage dealt to P2
        val damageToP2 = events.filterIsInstance<DamageDealt>().filter { it.target == Slot.p2() }
        assertEquals(0, damageToP2.size, "Levitate should block all Ground damage")
    }

    @Test
    fun `Levitate does not block non-Ground moves`() {
        val battleState =
            BattleState.singles(
                state(pokemon(normalSpecies)),
                state(pokemon(ghostSpecies), ability = Ability.LEVITATE),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(tackle),
                TurnChoice.UseMove(tackle),
            )

        val phase = MoveExecutionPhase(roll = fixedRoll, chanceCheck = noChance)
        val events = phase.resolve(battleState, choices)

        val blocked = events.filterIsInstance<AbilityBlocked>()
        assertEquals(0, blocked.size, "Levitate should not block Normal moves")
    }

    @Test
    fun `spread move with one Levitate target still hits others`() {
        val battleState =
            BattleState.doubles(
                // P1: Ground user + Normal ally
                state(pokemon(groundSpecies)),
                state(pokemon(normalSpecies)),
                // P2: Normal opponent + Ghost with Levitate (immune)
                state(pokemon(normalSpecies)),
                state(pokemon(ghostSpecies), ability = Ability.LEVITATE),
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

        val phase = MoveExecutionPhase(roll = fixedRoll, chanceCheck = noChance)
        val events = phase.resolve(battleState, choices)

        // Levitate blocks Earthquake for P2 slot 1
        val blocked = events.filterIsInstance<AbilityBlocked>()
        assertEquals(1, blocked.size)
        assertEquals(Slot.p2(1), blocked[0].slot)

        // Other targets still take damage (ally + P2 slot 0)
        val firstAttempt =
            events.indexOfFirst {
                it is MoveAttempted && (it as MoveAttempted).attacker == Slot.p1(0)
            }
        val damageAfterEarthquake =
            events.drop(firstAttempt + 1)
                .takeWhile { it is DamageDealt || it is PokemonFainted || it is AbilityBlocked }
                .filterIsInstance<DamageDealt>()

        assertEquals(2, damageAfterEarthquake.size, "Should hit ally and one opponent")
    }

    // --- Intimidate ---

    @Test
    fun `Intimidate lowers opponents attack on switch-in`() {
        val battleState =
            BattleState.singles(
                state(pokemon(normalSpecies)),
                state(pokemon(normalSpecies)),
                p1Bench = listOf(state(pokemon(normalSpecies), ability = Ability.INTIMIDATE)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(tackle),
            )

        val phase = SwitchPhase()
        val events = phase.resolve(battleState, choices)

        val triggered = events.filterIsInstance<AbilityTriggered>()
        assertEquals(1, triggered.size)
        assertEquals(Ability.INTIMIDATE, triggered[0].ability)

        val statChanged = events.filterIsInstance<StatChanged>()
        assertEquals(1, statChanged.size)
        assertEquals(Slot.p2(), statChanged[0].target)
        assertEquals(StatType.ATTACK, statChanged[0].stat)
        assertEquals(-1, statChanged[0].stages)
    }

    @Test
    fun `Intimidate in doubles lowers both opponents attack`() {
        val battleState =
            BattleState.doubles(
                state(pokemon(normalSpecies)),
                state(pokemon(normalSpecies)),
                state(pokemon(normalSpecies)),
                state(pokemon(normalSpecies)),
                p1Bench = listOf(state(pokemon(normalSpecies), ability = Ability.INTIMIDATE)),
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

        val phase = SwitchPhase()
        val events = phase.resolve(battleState, choices)

        val statChanged = events.filterIsInstance<StatChanged>()
        assertEquals(2, statChanged.size, "Both opponents should have Attack lowered")
        assertTrue(statChanged.all { it.stat == StatType.ATTACK && it.stages == -1 })
    }

    @Test
    fun `Intimidate triggers before moves in pipeline`() {
        val battleState =
            BattleState.singles(
                state(pokemon(normalSpecies)),
                state(pokemon(normalSpecies)),
                p1Bench = listOf(state(pokemon(normalSpecies), ability = Ability.INTIMIDATE)),
            )
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

        // Intimidate should fire during SwitchPhase, before MoveExecutionPhase
        val intimidateIndex = result.events.indexOfFirst { it is AbilityTriggered }
        val moveIndex = result.events.indexOfFirst { it is MoveAttempted }
        assertTrue(intimidateIndex < moveIndex, "Intimidate should trigger before moves")

        // P2's attack should be -1 when it attacks
        val statChanged = result.events.filterIsInstance<StatChanged>()
        assertEquals(-1, statChanged[0].stages)
    }

    // --- Drizzle ---

    @Test
    fun `Drizzle sets rain on switch-in`() {
        val battleState =
            BattleState.singles(
                state(pokemon(normalSpecies)),
                state(pokemon(normalSpecies)),
                p1Bench = listOf(state(pokemon(normalSpecies), ability = Ability.DRIZZLE)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(tackle),
            )

        val phase = SwitchPhase()
        val events = phase.resolve(battleState, choices)
        val newState = events.fold(battleState) { s, e -> e.apply(s) }

        val triggered = events.filterIsInstance<AbilityTriggered>()
        assertEquals(1, triggered.size)
        assertEquals(Ability.DRIZZLE, triggered[0].ability)

        assertEquals(Weather.RAIN, newState.field.weather)
        assertEquals(5, newState.field.weatherTurnsRemaining)
    }

    @Test
    fun `Drizzle overwrites existing weather`() {
        val battleState =
            BattleState.singles(
                state(pokemon(normalSpecies)),
                state(pokemon(normalSpecies)),
                p1Bench = listOf(state(pokemon(normalSpecies), ability = Ability.DRIZZLE)),
                field = FieldState(weather = Weather.SANDSTORM, weatherTurnsRemaining = 3),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(tackle),
            )

        val phase = SwitchPhase()
        val events = phase.resolve(battleState, choices)
        val newState = events.fold(battleState) { s, e -> e.apply(s) }

        assertEquals(Weather.RAIN, newState.field.weather, "Drizzle should overwrite sandstorm")
    }
}
