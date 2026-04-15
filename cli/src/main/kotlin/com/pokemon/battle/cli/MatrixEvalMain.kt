package com.pokemon.battle.cli

import com.pokemon.battle.ai.HeuristicAI
import com.pokemon.battle.ai.RandomAI
import com.pokemon.battle.ai.SideProviders
import com.pokemon.battle.ai.SidedAI
import com.pokemon.battle.ai.TypeAI
import com.pokemon.battle.analytics.BattleCorpus
import com.pokemon.battle.data.GenIIIRegistries
import com.pokemon.battle.data.GenIVRegistries
import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.DamageCalculator
import com.pokemon.battle.engine.Gen9VgcTeraRuleset
import com.pokemon.battle.engine.Registries
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.genIIIDamageCalculator
import com.pokemon.battle.engine.genVDamageCalculator
import com.pokemon.battle.loop.BattleLoop
import com.pokemon.battle.loop.ChoiceProvider
import com.pokemon.battle.loop.FaintReplacementProvider
import com.pokemon.battle.loop.InputResponder
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Species
import com.pokemon.battle.model.Type
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

// Distinct constants mixed into the per-battle seed so AI choice and engine rolls
// don't share a Random stream. Values are arbitrary; only their distinctness matters.
private const val SEED_SALT_ENGINE = 0x5A71_5A71_5A71_5A71L

// Matrix runner — reads as a long but linear script; extracting helpers would
// fragment the narrative.
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun main(args: Array<String>) {
    val battlesPerMatchup = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_BATTLES_PER_MATCHUP
    val genArg = args.getOrNull(1)?.lowercase() ?: "genv"
    val teraEnabled = args.drop(2).any { it.equals("tera", ignoreCase = true) }
    val registries =
        when (genArg) {
            "geniii", "gen3" -> GenIIIRegistries
            "geniv", "gen4" -> GenIVRegistries
            "genv", "gen5", "gen5+" -> GenVRegistries
            else -> error("Unknown gen: $genArg (expected gen3 | geniv | genv)")
        }
    val damageCalculatorFor: (Registries) -> DamageCalculator =
        when (genArg) {
            "geniii", "gen3" -> ::genIIIDamageCalculator
            else -> ::genVDamageCalculator
        }
    val outputTag = if (teraEnabled) "$genArg-tera" else genArg
    val outputDir = Path.of("battles/$outputTag")
    val recorder = FileBattleRecorder(outputDir)

    val pokedex = Pokedex.loadFromClasspath()
    // Tera-type picks: each mon tera'd into the type of one of its strongest STAB
    // moves, so HeuristicAI's "tera when move.type == tera type" rule triggers
    // on a move that also matches original types — unlocking the 2.0x Tera STAB.
    val teraTypes =
        if (teraEnabled) {
            mapOf(
                "Charizard" to Type.FIRE,
                "Garchomp" to Type.GROUND,
                "Lucario" to Type.FIGHTING,
                "Venusaur" to Type.POISON,
                "Blastoise" to Type.ICE,
                "Togekiss" to Type.FIGHTING,
            )
        } else {
            emptyMap()
        }
    val side1Pool = teamFor(pokedex, listOf("Charizard", "Garchomp", "Lucario"), teraTypes)
    val side2Pool = teamFor(pokedex, listOf("Venusaur", "Blastoise", "Togekiss"), teraTypes)

    val strategies = Strategy.entries
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

            // Items and abilities are set at state-construction time. Charizard's Life
            // Orb is legal in both gens; Venusaur's Rocky Helmet is Gen 5+ — in Gen IV
            // the item is unregistered so Venusaur holds it inertly. That asymmetry is
            // the entire point of the Gen IV vs Gen V comparison (diary 087).
            val side1Active =
                PokemonState(
                    side1Pool.pokemon[0],
                    currentHp = side1Pool.pokemon[0].maxHp,
                    ability = com.pokemon.battle.model.Ability.BLAZE,
                    item = com.pokemon.battle.model.Item.LIFE_ORB,
                )
            val side2Active =
                PokemonState(
                    side2Pool.pokemon[0],
                    currentHp = side2Pool.pokemon[0].maxHp,
                    ability = com.pokemon.battle.model.Ability.OVERGROW,
                    item = com.pokemon.battle.model.Item.ROCKY_HELMET,
                )
            val baseInitialState =
                BattleState.singles(
                    side1Active,
                    side2Active,
                    p1Bench = side1Pool.pokemon.drop(1).map { PokemonState(it, currentHp = it.maxHp) },
                    p2Bench = side2Pool.pokemon.drop(1).map { PokemonState(it, currentHp = it.maxHp) },
                )
            val initialState = if (teraEnabled) baseInitialState.copy(ruleset = Gen9VgcTeraRuleset) else baseInitialState

            // Dedicated Random for engine-side rolls (crits, chance checks). Separate
            // from the AI's Random so AI choice and engine rolls don't interfere;
            // derived from the same battle seed so whole battles reproduce.
            // Diary 089: without this, crits/chance-checks use Random.Default and
            // Gen V vs Gen IV comparisons carry unseeded-randomness noise.
            val engineRandom = Random(seed xor SEED_SALT_ENGINE)
            val pipeline =
                TurnPipeline(
                    listOf(
                        MoveOrderPhase(registries),
                        SwitchPhase(registries),
                        MoveExecutionPhase(
                            registries,
                            damageCalculator = damageCalculatorFor(registries),
                            roll = { range -> range.random(engineRandom) },
                            chanceCheck = { percent, _ -> engineRandom.nextInt(100) + 1 <= percent },
                        ),
                        EndOfTurnPhase(registries),
                    ),
                )

            val metadata =
                BattleMetadataFactory.forNewBattle(
                    formatTag = "matrixEval-$outputTag",
                    playerTags = mapOf(Side.SIDE_1.name to side1Strategy.name, Side.SIDE_2.name to side2Strategy.name),
                    // Record the RandomAI seed so every battle file is reproducible — drop
                    // this battleId back into a replay harness and the exact same choices
                    // fire. Diary 081's experiment-tracking alignment move.
                    clientInfo = "seed=$seed",
                )
            val result =
                BattleLoop(
                    pipeline = pipeline,
                    choiceProvider = providers,
                    faintReplacementProvider = providers,
                    registries = registries,
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
    side1Strategy: Strategy,
    side2Strategy: Strategy,
    side1Pool: MatrixTeamPool,
    side2Pool: MatrixTeamPool,
    seed: Long,
): SidedAI {
    fun provider(
        strategy: Strategy,
        pool: MatrixTeamPool,
    ): Triple<ChoiceProvider, FaintReplacementProvider, InputResponder?> {
        val movePools = pool.pokemon.associate { it.species.name to it.moves }
        return when (strategy) {
            Strategy.TypeAI -> TypeAI(movePools = movePools).let { Triple(it, it, it) }
            Strategy.RandomAI ->
                RandomAI(movePools = movePools, random = Random(seed))
                    .let { Triple(it, it, null) }
            Strategy.HeuristicAI -> HeuristicAI(movePools = movePools).let { Triple(it, it, it) }
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
    strategies: List<Strategy>,
) {
    val names = strategies.map { it.name }
    val results = BattleCorpus.matchupWinRates(corpus.asSequence())

    // Column header row
    print("  %-10s".format(""))
    for (s2 in names) {
        print(" %14s".format("vs $s2"))
    }
    println()

    for (s1 in names) {
        print("  %-10s".format(s1))
        for (s2 in names) {
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
    for (i in names.indices) {
        for (j in i + 1 until names.size) {
            val ab = results[names[i] to names[j]]
            val ba = results[names[j] to names[i]]
            if (ab != null && ba != null) {
                val combined =
                    (ab.sideAWins + ba.sideBWins).toDouble() /
                        (ab.battles + ba.battles).coerceAtLeast(1)
                println(
                    "${names[i]} overall vs ${names[j]}: " +
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
        "Charizard" to listOf(MoveDex.FLAMETHROWER, MoveDex.THUNDERBOLT, MoveDex.EARTHQUAKE, MoveDex.NASTY_PLOT),
        "Garchomp" to listOf(MoveDex.EARTHQUAKE, MoveDex.ICE_BEAM, MoveDex.FLAMETHROWER, MoveDex.SWORDS_DANCE),
        "Lucario" to listOf(MoveDex.AURA_SPHERE, MoveDex.ICE_BEAM, MoveDex.THUNDERBOLT, MoveDex.SWORDS_DANCE),
        "Venusaur" to listOf(MoveDex.SLUDGE_BOMB, MoveDex.EARTHQUAKE, MoveDex.ICE_BEAM, MoveDex.NASTY_PLOT),
        "Blastoise" to listOf(MoveDex.ICE_BEAM, MoveDex.EARTHQUAKE, MoveDex.AURA_SPHERE, MoveDex.SLUDGE_BOMB),
        "Togekiss" to listOf(MoveDex.AURA_SPHERE, MoveDex.ICE_BEAM, MoveDex.THUNDERBOLT, MoveDex.FLAMETHROWER),
    )

private fun teamFor(
    pokedex: Map<String, Species>,
    names: List<String>,
    teraTypes: Map<String, Type> = emptyMap(),
): MatrixTeamPool = MatrixTeamPool(names.map { Pokemon(pokedex[it]!!, level = 50, teraType = teraTypes[it]) })

private val Pokemon.moves: List<Move>
    get() = DEMO_MOVES[species.name] ?: emptyList()
