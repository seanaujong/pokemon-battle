package com.pokemon.battle

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.SwitchIn
import com.pokemon.battle.engine.SwitchOut
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatStages
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SelfSwitchTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val noChance: ChanceCheck = { _, _ -> false }
    private val fixedRoll: (IntRange) -> Int = { 100 }

    private fun pipeline() =
        TurnPipeline(
            listOf(
                MoveOrderPhase(),
                SwitchPhase(),
                MoveExecutionPhase(roll = fixedRoll, chanceCheck = noChance),
                EndOfTurnPhase(),
            ),
        )

    // ============================================================
    // Mainline Pokemon mechanics — reachable in normal play
    // ============================================================

    @Test
    fun `U-turn deals damage then switches attacker out`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                p1Bench = listOf(PokemonState(blastoise, currentHp = blastoise.maxHp)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.U_TURN, switchTo = 0),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        // Damage is dealt (U-turn vs Venusaur)
        val damage = result.events.filterIsInstance<DamageDealt>()
        assertTrue(damage.any { it.target == Slot.p2() && it.amount > 0 }, "U-turn should hit Venusaur")

        // Then the attacker switches out and Blastoise switches in
        val switchOut = result.events.filterIsInstance<SwitchOut>()
        val switchIn = result.events.filterIsInstance<SwitchIn>()
        assertEquals(1, switchOut.size)
        assertEquals(Slot.p1(), switchOut[0].slot)
        assertEquals(1, switchIn.size)
        assertEquals(Slot.p1(), switchIn[0].slot)
        assertEquals(0, switchIn[0].benchIndex)

        val finalState = result.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }
        assertEquals("Blastoise", finalState.pokemonFor(Slot.p1()).pokemon.species.name)
    }

    @Test
    fun `replacement triggers switch-in ability`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val gyarados = Pokemon(pokedex["Gyarados"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                p1Bench = listOf(PokemonState(gyarados, currentHp = gyarados.maxHp, ability = Ability.INTIMIDATE)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.U_TURN, switchTo = 0),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val abilityEvents = result.events.filterIsInstance<AbilityTriggered>()
        assertTrue(
            abilityEvents.any { it.slot == Slot.p1() && it.ability == Ability.INTIMIDATE },
            "Gyarados's Intimidate should trigger after switch-in",
        )
    }

    @Test
    fun `volatiles and stat stages clear on self-switch`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        // Charizard has +2 attack stages — should be cleared on switch
        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp, statStages = StatStages(attack = 2)),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                p1Bench = listOf(PokemonState(blastoise, currentHp = blastoise.maxHp)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.U_TURN, switchTo = 0),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val finalState = result.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }

        // Charizard is on the bench now — its stat stages should be reset
        val benched = finalState.benchFor(Side.SIDE_1).first { it.pokemon.species.name == "Charizard" }
        assertEquals(StatStages(), benched.statStages, "Stat stages should be cleared")
    }

    @Test
    fun `no switch when bench is empty`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // No bench
        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.U_TURN, switchTo = 0),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        // Damage still happens
        val damage = result.events.filterIsInstance<DamageDealt>()
        assertTrue(damage.any { it.target == Slot.p2() }, "U-turn damage still lands")

        // No switch
        assertTrue(result.events.filterIsInstance<SwitchOut>().isEmpty(), "No switch with empty bench")
        assertTrue(result.events.filterIsInstance<SwitchIn>().isEmpty())
    }

    @Test
    fun `U-turn without switchTo pauses for mid-turn target`() {
        // Phase 2 of diary 055: when the caller omits switchTo, the engine emits
        // NeedInput with a SwitchTargetRequest instead of silently skipping the switch.
        // The switch itself happens on resume with a SwitchTargetResponse.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                p1Bench = listOf(PokemonState(blastoise, currentHp = blastoise.maxHp)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.U_TURN),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)

        assertIs<com.pokemon.battle.engine.TurnResolution.NeedInput>(result)
        val request = result.state.pendingInput
        assertIs<com.pokemon.battle.engine.SwitchTargetRequest>(request)
        assertEquals(Slot.p1(), request.userSlot)
        assertEquals(listOf(0), request.eligibleBenchIndices)

        // Damage landed before the pause
        val damage = result.state.partialTurnEvents.filterIsInstance<DamageDealt>()
        assertTrue(damage.any { it.target == Slot.p2() }, "U-turn damage lands before the pause")
    }

    @Test
    fun `U-turn mid-turn pause resumes into the chosen bench target`() {
        // Phase 2: pause → caller answers SwitchTargetResponse(0) → resume → switch completes.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                p1Bench = listOf(PokemonState(blastoise, currentHp = blastoise.maxHp)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.U_TURN),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val pipeline = pipeline()
        val paused = pipeline.resolve(state, choices)
        assertIs<com.pokemon.battle.engine.TurnResolution.NeedInput>(paused)

        val resumed =
            pipeline.resume(
                paused.state,
                choices,
                com.pokemon.battle.engine.SwitchTargetResponse(benchIndex = 0),
            )
        assertIs<com.pokemon.battle.engine.TurnResolution.Completed>(resumed)

        // Full switch sequence landed on resume
        assertEquals(1, resumed.events.filterIsInstance<SwitchOut>().size)
        assertEquals(1, resumed.events.filterIsInstance<SwitchIn>().size)
        assertEquals(0, (resumed.events.filterIsInstance<SwitchIn>().first()).benchIndex)
    }

    @Test
    fun `Volt Switch blocked by Ground immunity does not switch`() {
        // Electric vs Ground = 0x (Ground is immune to Electric). Volt Switch lands no damage,
        // so the user does not switch out.
        val pikachu = Pokemon(pokedex["Pikachu"]!!, level = 50)
        val garchomp = Pokemon(pokedex["Garchomp"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(pikachu, currentHp = pikachu.maxHp),
                PokemonState(garchomp, currentHp = garchomp.maxHp),
                p1Bench = listOf(PokemonState(blastoise, currentHp = blastoise.maxHp)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.VOLT_SWITCH, switchTo = 0),
                TurnChoice.UseMove(MoveDex.EARTHQUAKE),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        // Volt Switch deals 0 damage to Garchomp (Dragon/Ground)
        val damage = result.events.filterIsInstance<DamageDealt>().filter { it.target == Slot.p2() }
        assertTrue(damage.all { it.amount == 0 }, "Volt Switch vs Ground = 0 damage")
        assertTrue(result.events.filterIsInstance<SwitchOut>().isEmpty(), "No switch when type-immune")
    }

    @Test
    fun `U-turn does not switch when target is already fainted`() {
        // Set up: Venusaur is already at 0 HP (fainted) when U-turn is used.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                // Venusaur starts at 0 HP — already fainted
                PokemonState(venusaur, currentHp = 0),
                p1Bench = listOf(PokemonState(blastoise, currentHp = blastoise.maxHp)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.U_TURN, switchTo = 0),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        assertTrue(
            result.events.filterIsInstance<DamageDealt>().none { it.target == Slot.p2() },
            "U-turn skips a fainted target",
        )
        assertTrue(
            result.events.filterIsInstance<SwitchOut>().isEmpty(),
            "No switch when no damage was dealt (target was fainted)",
        )
    }

    @Test
    fun `U-turn does not switch when blocked by Protect`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                p1Bench = listOf(PokemonState(blastoise, currentHp = blastoise.maxHp)),
            )
        // Venusaur Protects (+4 priority → goes first), Charizard's U-turn is blocked
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.U_TURN, switchTo = 0),
                TurnChoice.UseMove(MoveDex.PROTECT),
            )

        // chanceCheck lets Protect succeed
        val protectSucceeds: ChanceCheck = { _, reason ->
            reason == com.pokemon.battle.model.FailReason.PROTECT_FAILED
        }
        val pipe =
            TurnPipeline(
                listOf(
                    MoveOrderPhase(),
                    SwitchPhase(),
                    MoveExecutionPhase(roll = fixedRoll, chanceCheck = protectSucceeds),
                    EndOfTurnPhase(),
                ),
            )
        val result = pipe.resolveToCompletion(state, choices)

        assertTrue(result.events.filterIsInstance<SwitchOut>().isEmpty(), "No switch when Protect blocks the move")
    }

    @Test
    fun `next turn the replacement can act`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
                p1Bench = listOf(PokemonState(blastoise, currentHp = blastoise.maxHp)),
            )

        // Turn 1: U-turn → Blastoise comes in
        val turn1Choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.U_TURN, switchTo = 0),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )
        val turn1 = pipeline().resolveToCompletion(state, turn1Choices)
        val afterTurn1 = turn1.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }
        assertEquals("Blastoise", afterTurn1.pokemonFor(Slot.p1()).pokemon.species.name)
        assertFalse(afterTurn1.pokemonFor(Slot.p1()).isFainted)

        // Turn 2: Blastoise uses Ice Beam
        val turn2Choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )
        val turn2 = pipeline().resolveToCompletion(afterTurn1, turn2Choices)
        val damage = turn2.events.filterIsInstance<DamageDealt>()
        assertTrue(damage.any { it.target == Slot.p2() && it.amount > 0 }, "Blastoise should attack on turn 2")
    }
}
