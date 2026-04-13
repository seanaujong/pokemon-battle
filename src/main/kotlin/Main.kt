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

    // Both sides use TypeAI with diverse move pools
    val side1Moves = mapOf(
        Slot.p1() to listOf(MoveDex.FLAMETHROWER, MoveDex.THUNDERBOLT, MoveDex.EARTHQUAKE, MoveDex.ICE_BEAM)
    )
    val side2Moves = mapOf(
        Slot.p2() to listOf(MoveDex.SLUDGE_BOMB, MoveDex.EARTHQUAKE, MoveDex.ICE_BEAM, MoveDex.AURA_SPHERE)
    )

    val side1AI = TypeAI(movePools = side1Moves)
    val side2AI = TypeAI(movePools = side2Moves)

    val pipeline = TurnPipeline(listOf(
        MoveOrderPhase(),
        SwitchPhase(),
        MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false }),
        EndOfTurnPhase()
    ))

    val result = BattleLoop(
        pipeline = pipeline,
        choiceProvider = { state ->
            val p1 = side1AI.getChoices(state)
            val p2 = side2AI.getChoices(state)
            TurnChoices(p1.choices + p2.choices)
        },
        faintReplacementProvider = { state, slot ->
            if (slot.side == Side.SIDE_1) side1AI.getReplacement(state, slot)
            else side2AI.getReplacement(state, slot)
        },
        maxTurns = 30
    ).run(initialState)

    renderBattle(result, initialState).forEach(::println)
}
