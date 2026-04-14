package com.pokemon.battle.cli

import com.pokemon.battle.ai.RandomAI
import com.pokemon.battle.ai.SideProviders
import com.pokemon.battle.ai.SidedAI
import com.pokemon.battle.ai.TypeAI
import com.pokemon.battle.analytics.BattleCorpus
import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.loop.BattleLoop
import com.pokemon.battle.loop.ChoiceProvider
import com.pokemon.battle.loop.FaintReplacementProvider
import com.pokemon.battle.loop.InputResponder
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Species
import com.pokemon.battle.persistence.BattleLoader
import com.pokemon.battle.persistence.BattleMetadataFactory
import com.pokemon.battle.persistence.FileBattleRecorder
import com.pokemon.battle.persistence.withEnded
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import java.nio.file.Path
import kotlin.random.Random

/**
 * Runs every AI-vs-AI matchup in the registered-strategies catalog N times,
 * writes each battle to `./battles/` with `playerTags` set, then prints a
 * matchup matrix.
 *
 * Motivating question (diary 078-era follow-up): is TypeAI actually stronger
 * than RandomAI, or is the prior batch's 100% win rate a matchup artifact?
 * Seeing every orientation + symmetry tells us.
 *
 * Usage: `./gradlew :cli:matrixEval --args="20"` to run 20 battles per
 * matchup (default 20).
 */
private const val DEFAULT_BATTLES_PER_MATCHUP = 20
private const val MAX_TURNS = 30
private const val DIVIDER_LEN = 72

// Matrix runner — reads as a long but linear script; extracting helpers would
// fragment the narrative.
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun main(args: Array<String>) {
    val battlesPerMatchup = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_BATTLES_PER_MATCHUP
    val outputDir = Path.of("battles")
    val recorder = FileBattleRecorder(outputDir)

    val pokedex = Pokedex.loadFromClasspath()
    val side1Pool = teamFor(pokedex, listOf("Charizard", "Garchomp", "Lucario"))
    val side2Pool = teamFor(pokedex, listOf("Venusaur", "Blastoise", "Togekiss"))

    val strategies = listOf("TypeAI", "RandomAI")
    val matchups = strategies.flatMap { s1 -> strategies.map { s2 -> s1 to s2 } }

    println("Matrix eval: ${strategies.size}x${strategies.size} matchups × $battlesPerMatchup battles")
    println("Output dir: ${outputDir.toAbsolutePath()}")
    println()

    val totalBattles = matchups.size * battlesPerMatchup
    var done = 0
    for ((side1Strategy, side2Strategy) in matchups) {
        repeat(battlesPerMatchup) { i ->
            val seed = (done * 31L) + i
            val providers =
                buildSidedAI(
                    side1Strategy = side1Strategy,
                    side2Strategy = side2Strategy,
                    side1Pool = side1Pool,
                    side2Pool = side2Pool,
                    seed = seed,
                )

            val initialState =
                BattleState.singles(
                    PokemonState(side1Pool.pokemon[0], currentHp = side1Pool.pokemon[0].maxHp),
                    PokemonState(side2Pool.pokemon[0], currentHp = side2Pool.pokemon[0].maxHp),
                    p1Bench = side1Pool.pokemon.drop(1).map { PokemonState(it, currentHp = it.maxHp) },
                    p2Bench = side2Pool.pokemon.drop(1).map { PokemonState(it, currentHp = it.maxHp) },
                )

            val pipeline =
                TurnPipeline(
                    listOf(
                        MoveOrderPhase(GenVRegistries),
                        SwitchPhase(GenVRegistries),
                        MoveExecutionPhase(GenVRegistries),
                        EndOfTurnPhase(GenVRegistries),
                    ),
                )

            val metadata =
                BattleMetadataFactory.forNewBattle(
                    formatTag = "matrixEval",
                    playerTags = mapOf(Side.SIDE_1.name to side1Strategy, Side.SIDE_2.name to side2Strategy),
                )
            val result =
                BattleLoop(
                    pipeline = pipeline,
                    choiceProvider = providers,
                    faintReplacementProvider = providers,
                    registries = GenVRegistries,
                    maxTurns = MAX_TURNS,
                ).run(initialState)
            recorder.record(result, metadata.withEnded())

            done++
            if (done % 10 == 0 || done == totalBattles) {
                print("\r  $done/$totalBattles")
            }
        }
    }
    println()

    println()
    println("=".repeat(DIVIDER_LEN))
    println("Matchup matrix (side 1 vs side 2, win rate of side 1)")
    println("=".repeat(DIVIDER_LEN))
    val corpus = BattleLoader.loadAll(outputDir).toList()
    printMatchupMatrix(corpus, strategies)
}

private fun buildSidedAI(
    side1Strategy: String,
    side2Strategy: String,
    side1Pool: MatrixTeamPool,
    side2Pool: MatrixTeamPool,
    seed: Long,
): SidedAI {
    fun provider(
        strategy: String,
        pool: MatrixTeamPool,
    ): Triple<ChoiceProvider, FaintReplacementProvider, InputResponder?> {
        val movePools = pool.pokemon.associate { it.species.name to it.moves }
        return when (strategy) {
            "TypeAI" -> TypeAI(movePools = movePools).let { Triple(it, it, it) }
            "RandomAI" ->
                RandomAI(movePools = movePools, random = Random(seed))
                    .let { Triple(it, it, null) }
            else -> error("Unknown strategy: $strategy")
        }
    }

    val (p1Choice, p1Faint, p1Input) = provider(side1Strategy, side1Pool)
    val (p2Choice, p2Faint, p2Input) = provider(side2Strategy, side2Pool)

    return SidedAI(
        side1 = SideProviders(p1Choice, p1Faint, p1Input),
        side2 = SideProviders(p2Choice, p2Faint, p2Input),
    )
}

@Suppress("NestedBlockDepth") // Matrix display is inherently doubly-nested (rows × columns).
private fun printMatchupMatrix(
    corpus: List<com.pokemon.battle.persistence.PersistedBattle>,
    strategies: List<String>,
) {
    val results = BattleCorpus.matchupWinRates(corpus.asSequence())

    // Column header row
    print("  %-10s".format(""))
    for (s2 in strategies) {
        print(" %14s".format("vs $s2"))
    }
    println()

    for (s1 in strategies) {
        print("  %-10s".format(s1))
        for (s2 in strategies) {
            val result = results[s1 to s2]
            val cell =
                if (result == null) {
                    "  (no data)   "
                } else {
                    val rate = (result.sideAWinRate * 100).let { "%.0f%%".format(it) }
                    val tally = "${result.sideAWins}/${result.battles}"
                    "$rate ($tally)"
                }
            print(" %14s".format(cell))
        }
        println()
    }
    println()
    println("Rows = side 1 strategy; columns = side 2 strategy. Cell = side 1 win rate.")
    println()

    // Sanity check: opposite corners should roughly sum to 100% minus draws.
    for (i in strategies.indices) {
        for (j in i + 1 until strategies.size) {
            val ab = results[strategies[i] to strategies[j]]
            val ba = results[strategies[j] to strategies[i]]
            if (ab != null && ba != null) {
                val combined =
                    (ab.sideAWins + ba.sideBWins).toDouble() /
                        (ab.battles + ba.battles).coerceAtLeast(1)
                println(
                    "${strategies[i]} overall vs ${strategies[j]}: " +
                        "${"%.1f".format(combined * 100)}% across ${ab.battles + ba.battles} battles " +
                        "(orientation-averaged)",
                )
            }
        }
    }
}

private data class MatrixTeamPool(val pokemon: List<Pokemon>)

private val DEMO_MOVES: Map<String, List<Move>> =
    mapOf(
        "Charizard" to listOf(MoveDex.FLAMETHROWER, MoveDex.THUNDERBOLT, MoveDex.EARTHQUAKE, MoveDex.ICE_BEAM),
        "Garchomp" to listOf(MoveDex.EARTHQUAKE, MoveDex.ICE_BEAM, MoveDex.FLAMETHROWER, MoveDex.SWORDS_DANCE),
        "Lucario" to listOf(MoveDex.AURA_SPHERE, MoveDex.ICE_BEAM, MoveDex.THUNDERBOLT, MoveDex.SWORDS_DANCE),
        "Venusaur" to listOf(MoveDex.SLUDGE_BOMB, MoveDex.EARTHQUAKE, MoveDex.ICE_BEAM, MoveDex.GROWL),
        "Blastoise" to listOf(MoveDex.ICE_BEAM, MoveDex.EARTHQUAKE, MoveDex.AURA_SPHERE, MoveDex.SLUDGE_BOMB),
        "Togekiss" to listOf(MoveDex.AURA_SPHERE, MoveDex.ICE_BEAM, MoveDex.THUNDERBOLT, MoveDex.FLAMETHROWER),
    )

private fun teamFor(
    pokedex: Map<String, Species>,
    names: List<String>,
): MatrixTeamPool = MatrixTeamPool(names.map { Pokemon(pokedex[it]!!, level = 50) })

private val Pokemon.moves: List<Move>
    get() = DEMO_MOVES[species.name] ?: emptyList()
