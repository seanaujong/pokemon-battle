import com.pokemon.battle.data.*
import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*
import com.pokemon.battle.phase.*
import com.pokemon.battle.loop.*
import com.pokemon.battle.render.*

fun main() {
    val pokedex = Pokedex.loadFromClasspath()

    val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
    val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
    val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

    val initialState = BattleState.singles(
        PokemonState(charizard, currentHp = charizard.maxHp),
        PokemonState(venusaur, currentHp = venusaur.maxHp),
        p2Bench = listOf(PokemonState(blastoise, currentHp = blastoise.maxHp))
    )

    val pipeline = TurnPipeline(listOf(
        MoveOrderPhase(),
        SwitchPhase(),
        MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false }),
        EndOfTurnPhase()
    ))

    val result = BattleLoop(
        pipeline = pipeline,
        choiceProvider = { TurnChoices.singles(
            TurnChoice.UseMove(MoveDex.FLAMETHROWER),
            TurnChoice.UseMove(MoveDex.SLUDGE_BOMB)
        )},
        faintReplacementProvider = { _, _ -> 0 },
        maxTurns = 10
    ).run(initialState)

    renderBattle(result, initialState).forEach(::println)
}
