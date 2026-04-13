import com.pokemon.battle.ai.*
import com.pokemon.battle.data.*
import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*
import com.pokemon.battle.phase.*
import com.pokemon.battle.loop.*
import com.pokemon.battle.render.*

fun main() {
    val pokedex = Pokedex.loadFromClasspath()

    val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
    val garchomp = Pokemon(pokedex["Garchomp"]!!, level = 50)
    val lucario = Pokemon(pokedex["Lucario"]!!, level = 50)

    val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
    val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)
    val togekiss = Pokemon(pokedex["Togekiss"]!!, level = 50)

    val initialState = BattleState.singles(
        PokemonState(charizard, currentHp = charizard.maxHp),
        PokemonState(venusaur, currentHp = venusaur.maxHp),
        p1Bench = listOf(
            PokemonState(garchomp, currentHp = garchomp.maxHp),
            PokemonState(lucario, currentHp = lucario.maxHp)
        ),
        p2Bench = listOf(
            PokemonState(blastoise, currentHp = blastoise.maxHp),
            PokemonState(togekiss, currentHp = togekiss.maxHp)
        )
    )

    val side1AI = TypeAI(movePools = mapOf(
        "Charizard" to listOf(MoveDex.FLAMETHROWER, MoveDex.THUNDERBOLT, MoveDex.EARTHQUAKE, MoveDex.ICE_BEAM),
        "Garchomp" to listOf(MoveDex.EARTHQUAKE, MoveDex.ICE_BEAM, MoveDex.FLAMETHROWER, MoveDex.SWORDS_DANCE),
        "Lucario" to listOf(MoveDex.AURA_SPHERE, MoveDex.ICE_BEAM, MoveDex.THUNDERBOLT, MoveDex.SWORDS_DANCE)
    ))
    val side2AI = TypeAI(movePools = mapOf(
        "Venusaur" to listOf(MoveDex.SLUDGE_BOMB, MoveDex.EARTHQUAKE, MoveDex.ICE_BEAM, MoveDex.GROWL),
        "Blastoise" to listOf(MoveDex.ICE_BEAM, MoveDex.EARTHQUAKE, MoveDex.AURA_SPHERE, MoveDex.SLUDGE_BOMB),
        "Togekiss" to listOf(MoveDex.AURA_SPHERE, MoveDex.ICE_BEAM, MoveDex.THUNDERBOLT, MoveDex.FLAMETHROWER)
    ))

    val ai = SidedAI(
        side1 = side1AI to side1AI,
        side2 = side2AI to side2AI
    )

    val pipeline = TurnPipeline(listOf(
        MoveOrderPhase(),
        SwitchPhase(),
        MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false }),
        EndOfTurnPhase()
    ))

    val result = BattleLoop(
        pipeline = pipeline,
        choiceProvider = ai,
        faintReplacementProvider = ai,
        maxTurns = 30
    ).run(initialState)

    renderBattle(result, initialState).forEach(::println)
}
