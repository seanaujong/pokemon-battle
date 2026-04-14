package com.pokemon.battle.cli

import com.pokemon.battle.ai.RandomAI
import com.pokemon.battle.ai.SideProviders
import com.pokemon.battle.ai.SidedAI
import com.pokemon.battle.ai.TypeAI
import com.pokemon.battle.analytics.BattleAnalyzer
import com.pokemon.battle.analytics.BattleCorpus
import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.loop.BattleLoop
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
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
 * Runs N AI-vs-AI battles, persists each to `./battles/`, and prints aggregate
 * stats over the corpus. Diary 078's motivating question: does TypeAI actually
 * beat RandomAI, and what moves drive the result?
 *
 * Side 1 plays TypeAI (picks the highest-effectiveness move); side 2 plays
 * RandomAI. Teams and move pools match `DemoMain` so the only changing variable
 * is the choice policy.
 */
private const val DEFAULT_BATTLES = 100
private const val MAX_TURNS = 30
private const val DIVIDER_LEN = 60

@Suppress("LongMethod") // Batch setup reuses DemoMain's team shape inline; extracting a helper would just move the noise.
fun main(args: Array<String>) {
    val battleCount = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_BATTLES
    val outputDir = Path.of("battles")

    val pokedex = Pokedex.loadFromClasspath()
    val side1Pool = teamFor(pokedex, listOf("Charizard", "Garchomp", "Lucario"))
    val side2Pool = teamFor(pokedex, listOf("Venusaur", "Blastoise", "Togekiss"))

    val recorder = FileBattleRecorder(outputDir)

    println("Running $battleCount TypeAI-vs-RandomAI battles → ${outputDir.toAbsolutePath()}")
    repeat(battleCount) { i ->
        val random = Random(seed = i.toLong())
        val side1 = TypeAI(movePools = side1Pool.pokemon.associate { it.species.name to it.moves })
        val side2 =
            RandomAI(
                movePools = side2Pool.pokemon.associate { it.species.name to it.moves },
                random = random,
            )

        val providers =
            SidedAI(
                // TypeAI handles InputResponder (U-turn switch targets); RandomAI doesn't,
                // which is fine because RandomAI's pool in this batch has no self-switch moves.
                side1 = SideProviders(side1, side1, side1),
                side2 = SideProviders(side2, side2),
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

        val metadata = BattleMetadataFactory.forNewBattle(formatTag = "batchDemo-typeai-vs-randomai")
        val result =
            BattleLoop(
                pipeline = pipeline,
                choiceProvider = providers,
                faintReplacementProvider = providers,
                registries = GenVRegistries,
                maxTurns = MAX_TURNS,
            ).run(initialState)
        recorder.record(result, metadata.withEnded())

        if ((i + 1) % 10 == 0 || i + 1 == battleCount) {
            print("\r  ${i + 1}/$battleCount")
        }
    }
    println()

    println()
    println("=".repeat(DIVIDER_LEN))
    println("Corpus aggregates")
    println("=".repeat(DIVIDER_LEN))
    val corpus = BattleLoader.loadAll(outputDir)
    printAggregates(corpus.toList())
}

private fun printAggregates(corpus: List<com.pokemon.battle.persistence.PersistedBattle>) {
    val winCounts = BattleCorpus.winsBySide(corpus.asSequence())
    val winRates = BattleCorpus.winRate(corpus.asSequence())
    val moveUsage = BattleCorpus.moveUsage(corpus.asSequence())
    val crits = BattleCorpus.criticalHitCount(corpus.asSequence())
    val kos = BattleCorpus.koCount(corpus.asSequence())

    println("Battles: ${corpus.size}")
    println()
    println("Win counts:")
    winCounts.toSortedMap(compareBy { it?.name ?: "" }).forEach { (side, n) ->
        val pct = (winRates[side] ?: 0.0) * 100.0
        println("  ${side?.name ?: "DRAW/TURN_LIMIT"}: $n (${"%.1f".format(pct)}%)")
    }
    println()
    println("Total KOs observed: $kos")
    println("Total crits observed: $crits")
    println()
    println("Top 10 moves by attempts:")
    moveUsage.entries.sortedByDescending { it.value }.take(10).forEach { (name, n) ->
        println("  $name: $n")
    }
    println()
    println("Avg turns per battle: ${"%.1f".format(corpus.map { BattleAnalyzer.analyze(resultStub(it)).turnsPlayed }.average())}")
}

private fun resultStub(battle: com.pokemon.battle.persistence.PersistedBattle): com.pokemon.battle.loop.BattleResult {
    // Convert back to a BattleResult shape that BattleAnalyzer can consume. We only
    // need the turn count for the average; full state reconstruction isn't required.
    val turns =
        battle.turns.map {
            com.pokemon.battle.loop.TurnRecord(
                turnNumber = it.turnNumber,
                events = it.events.map { e -> e.toDomain() },
                replacementEvents = it.replacementEvents.map { e -> e.toDomain() },
            )
        }
    return com.pokemon.battle.loop.BattleResult(
        winner = battle.winner,
        finalState = BattleState.singles(stubState(), stubState()),
        turnHistory = turns,
    )
}

private fun stubState(): PokemonState {
    // Placeholder state — BattleAnalyzer reads only turnHistory for the aggregates this main uses.
    val stub = Species("stub", listOf(com.pokemon.battle.model.Type.NORMAL), 1, 1, 1, 1, 1, 1)
    return PokemonState(Pokemon(stub, level = 1), currentHp = 1)
}

private data class TeamPool(val pokemon: List<Pokemon>)

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
): TeamPool {
    val pokemon =
        names.map { name ->
            Pokemon(pokedex[name]!!, level = 50).also {
                // move pool attached via name lookup in DEMO_MOVES; TeamPool.pokemon
                // is a convenience holder. The real move pool mapping is rebuilt below.
            }
        }
    return TeamPool(pokemon)
}

private val Pokemon.moves: List<Move>
    get() = DEMO_MOVES[species.name] ?: emptyList()
