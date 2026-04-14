package com.pokemon.battle.cli

import com.pokemon.battle.ai.SideProviders
import com.pokemon.battle.ai.SidedAI
import com.pokemon.battle.ai.TypeAI
import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.loop.BattleLoop
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import com.pokemon.battle.render.renderBattle

@Suppress("LongMethod") // Demo setup — intentionally shows full battle configuration
fun main() {
    val pokedex = Pokedex.loadFromClasspath()

    val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
    val garchomp = Pokemon(pokedex["Garchomp"]!!, level = 50)
    val lucario = Pokemon(pokedex["Lucario"]!!, level = 50)

    val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
    val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)
    val togekiss = Pokemon(pokedex["Togekiss"]!!, level = 50)

    val initialState =
        BattleState.singles(
            PokemonState(charizard, currentHp = charizard.maxHp),
            PokemonState(venusaur, currentHp = venusaur.maxHp),
            p1Bench =
                listOf(
                    PokemonState(garchomp, currentHp = garchomp.maxHp),
                    PokemonState(lucario, currentHp = lucario.maxHp),
                ),
            p2Bench =
                listOf(
                    PokemonState(blastoise, currentHp = blastoise.maxHp),
                    PokemonState(togekiss, currentHp = togekiss.maxHp),
                ),
        )

    val side1AI =
        TypeAI(
            movePools =
                mapOf(
                    "Charizard" to listOf(MoveDex.FLAMETHROWER, MoveDex.THUNDERBOLT, MoveDex.EARTHQUAKE, MoveDex.ICE_BEAM),
                    "Garchomp" to listOf(MoveDex.EARTHQUAKE, MoveDex.ICE_BEAM, MoveDex.FLAMETHROWER, MoveDex.SWORDS_DANCE),
                    "Lucario" to listOf(MoveDex.AURA_SPHERE, MoveDex.ICE_BEAM, MoveDex.THUNDERBOLT, MoveDex.SWORDS_DANCE),
                ),
        )
    val side2AI =
        TypeAI(
            movePools =
                mapOf(
                    "Venusaur" to listOf(MoveDex.SLUDGE_BOMB, MoveDex.EARTHQUAKE, MoveDex.ICE_BEAM, MoveDex.GROWL),
                    "Blastoise" to listOf(MoveDex.ICE_BEAM, MoveDex.EARTHQUAKE, MoveDex.AURA_SPHERE, MoveDex.SLUDGE_BOMB),
                    "Togekiss" to listOf(MoveDex.AURA_SPHERE, MoveDex.ICE_BEAM, MoveDex.THUNDERBOLT, MoveDex.FLAMETHROWER),
                ),
        )

    val ai =
        SidedAI(
            side1 = SideProviders(side1AI, side1AI, side1AI),
            side2 = SideProviders(side2AI, side2AI, side2AI),
        )

    val pipeline =
        TurnPipeline(
            listOf(
                MoveOrderPhase(GenVRegistries),
                SwitchPhase(GenVRegistries),
                MoveExecutionPhase(GenVRegistries, roll = { 100 }, chanceCheck = { _, _ -> false }),
                EndOfTurnPhase(GenVRegistries),
            ),
        )

    val result =
        BattleLoop(
            pipeline = pipeline,
            choiceProvider = ai,
            faintReplacementProvider = ai,
            registries = GenVRegistries,
            maxTurns = 30,
        ).run(initialState)

    renderBattle(result, initialState).forEach(::println)
}
