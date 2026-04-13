package com.pokemon.battle

import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*
import com.pokemon.battle.phase.*
import com.pokemon.battle.loop.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameLoopTest {

    private val speciesA = Species("Attacker", listOf(Type.FIRE), 80, 120, 80, 80, 80, 100)
    private val speciesB = Species("Defender", listOf(Type.WATER), 100, 80, 100, 80, 100, 60)
    private val speciesC = Species("Reserve", listOf(Type.GRASS), 80, 100, 80, 80, 80, 80)

    private fun pokemon(species: Species) = Pokemon(species, level = 50)
    private fun state(pokemon: Pokemon) = PokemonState(pokemon, currentHp = pokemon.maxHp)
    private fun state(pokemon: Pokemon, hp: Int) = PokemonState(pokemon, currentHp = hp)

    private val tackle = Move("Tackle", Type.NORMAL, MoveCategory.PHYSICAL, 40)
    private val flamethrower = Move("Flamethrower", Type.FIRE, MoveCategory.SPECIAL, 90)

    private val fixedRoll: (IntRange) -> Int = { 100 }
    private val noChance: ChanceCheck = { _, _ -> false }

    private fun defaultPipeline() = TurnPipeline(listOf(
        MoveOrderPhase(),
        SwitchPhase(),
        MoveExecutionPhase(roll = fixedRoll, chanceCheck = noChance),
        EndOfTurnPhase()
    ))

    // --- Simple KO ---

    @Test
    fun `battle ends when one side has no Pokemon left`() {
        // Attacker with 1 HP vs Defender — Attacker is faster, KOs Defender? No, tackle is weak.
        // Let's use a low-HP defender to guarantee a KO.
        val battleState = BattleState.singles(
            state(pokemon(speciesA)),
            state(pokemon(speciesB), hp = 1)
        )

        val result = runBattle(battleState, listOf(
            TurnChoices.singles(TurnChoice.UseMove(tackle), TurnChoice.UseMove(tackle))
        ))

        assertEquals(Side.SIDE_1, result.winner)
        assertEquals(1, result.turnHistory.size, "Should end after 1 turn")
        assertTrue(result.finalState.pokemonFor(Slot.p2()).isFainted)
    }

    // --- Multi-turn battle ---

    @Test
    fun `battle runs multiple turns until a Pokemon faints`() {
        val battleState = BattleState.singles(
            state(pokemon(speciesA)),
            state(pokemon(speciesB))
        )

        // Both use Tackle every turn — eventually one faints
        val result = runBattle(battleState, repeatChoice(
            TurnChoices.singles(TurnChoice.UseMove(tackle), TurnChoice.UseMove(tackle)),
            maxTurns = 20
        ))

        assertTrue(result.winner != null, "Someone should win")
        assertTrue(result.turnHistory.size > 1, "Should take multiple turns")
    }

    // --- Faint replacement ---

    @Test
    fun `fainted Pokemon is replaced from bench and battle continues`() {
        val battleState = BattleState.singles(
            state(pokemon(speciesA)),
            state(pokemon(speciesB), hp = 1),
            p2Bench = listOf(state(pokemon(speciesC)))
        )

        // Turn 1: Attacker KOs Defender. Replacement comes in.
        // Turn 2+: Battle continues with reserve.
        val result = runBattle(battleState, repeatChoice(
            TurnChoices.singles(TurnChoice.UseMove(tackle), TurnChoice.UseMove(tackle)),
            maxTurns = 20
        ))

        assertEquals(Side.SIDE_1, result.winner, "Side 1 should eventually win")
        assertTrue(result.turnHistory.size > 1, "Battle should continue after replacement")

        // Verify a faint replacement SwitchIn happened (in replacement events, not pipeline events)
        val allReplacements = result.turnHistory.flatMap { it.replacementEvents }
        assertTrue(allReplacements.any { it is SwitchIn }, "Should have a faint replacement SwitchIn")
    }

    // --- Full 3v3 battle ---

    @Test
    fun `3v3 battle with faints and replacements`() {
        // Side 1: strong attacker with 2 reserves
        // Side 2: three weak Pokemon (1 HP each)
        val battleState = BattleState.singles(
            state(pokemon(speciesA)),
            state(pokemon(speciesB), hp = 1),
            p1Bench = listOf(state(pokemon(speciesC)), state(pokemon(speciesC))),
            p2Bench = listOf(state(pokemon(speciesB), hp = 1), state(pokemon(speciesB), hp = 1))
        )

        val result = runBattle(battleState, repeatChoice(
            TurnChoices.singles(TurnChoice.UseMove(tackle), TurnChoice.UseMove(tackle)),
            maxTurns = 20
        ))

        assertEquals(Side.SIDE_1, result.winner)
        assertTrue(result.finalState.isDefeated(Side.SIDE_2))

        // All 3 of side 2's Pokemon should have fainted (1 active + 2 bench)
        val faints = result.turnHistory.flatMap { it.events }.filterIsInstance<PokemonFainted>()
        val side2Faints = faints.count { it.slot.side == Side.SIDE_2 }
        assertEquals(3, side2Faints, "All 3 of side 2's Pokemon should faint")
    }

    // --- Draw ---

    @Test
    fun `both sides fainting simultaneously is a draw`() {
        // Both at 1 HP, both use Tackle. Faster one KOs the other first...
        // Actually in our pipeline, the faster one goes first and the slower one faints,
        // so it's not simultaneous. Let's use end-of-turn for a real draw.
        // Both poisoned, both at low HP, neither attacks (use a no-damage status move).
        val swordsDance = Move("Swords Dance", Type.NORMAL, MoveCategory.STATUS, 0,
            target = MoveTarget.SELF, effects = listOf(MoveEffect.StatBoost(StatType.ATTACK, 2)))

        val battleState = BattleState.singles(
            PokemonState(pokemon(speciesA), currentHp = 1, status = StatusCondition.BURN),
            PokemonState(pokemon(speciesB), currentHp = 1, status = StatusCondition.BURN)
        )

        val result = runBattle(battleState, listOf(
            TurnChoices.singles(TurnChoice.UseMove(swordsDance), TurnChoice.UseMove(swordsDance))
        ))

        assertNull(result.winner, "Both fainting should be a draw")
        assertTrue(result.finalState.isDefeated(Side.SIDE_1))
        assertTrue(result.finalState.isDefeated(Side.SIDE_2))
    }

    // --- Turn limit ---

    @Test
    fun `turn limit prevents infinite loops`() {
        val battleState = BattleState.singles(
            state(pokemon(speciesA)),
            state(pokemon(speciesB))
        )

        // Use Swords Dance every turn — no damage, battle never ends naturally
        val swordsDance = Move("Swords Dance", Type.NORMAL, MoveCategory.STATUS, 0,
            target = MoveTarget.SELF, effects = listOf(MoveEffect.StatBoost(StatType.ATTACK, 2)))

        val loop = BattleLoop(
            pipeline = defaultPipeline(),
            choiceProvider = { TurnChoices.singles(
                TurnChoice.UseMove(swordsDance), TurnChoice.UseMove(swordsDance))
            },
            faintReplacementProvider = { _, _ -> 0 },
            maxTurns = 5
        )

        val result = loop.run(battleState)
        assertNull(result.winner, "No winner when turn limit reached")
        assertEquals(5, result.turnHistory.size)
    }

    // --- Helpers ---

    private fun runBattle(state: BattleState, choices: List<TurnChoices>): BattleResult {
        var choiceIndex = 0
        return BattleLoop(
            pipeline = defaultPipeline(),
            choiceProvider = {
                val choice = choices[choiceIndex.coerceAtMost(choices.lastIndex)]
                choiceIndex++
                choice
            },
            faintReplacementProvider = { _, _ -> 0 }  // always send in first bench Pokemon
        ).run(state)
    }

    private fun repeatChoice(choice: TurnChoices, maxTurns: Int): List<TurnChoices> =
        List(maxTurns) { choice }
}
