package com.pokemon.battle.cli

import com.pokemon.battle.ai.SideProviders
import com.pokemon.battle.ai.SidedAI
import com.pokemon.battle.ai.TypeAI
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.SwitchIn
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.resolveSwitchInAbility
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Side
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import com.pokemon.battle.render.TextRenderer

private const val MAX_TURNS = 100

fun main() {
    val pokedex = Pokedex.loadFromClasspath()
    val charizard = Pokemon(pokedex.getValue("Charizard"), level = 50)
    val garchomp = Pokemon(pokedex.getValue("Garchomp"), level = 50)
    val lucario = Pokemon(pokedex.getValue("Lucario"), level = 50)
    val venusaur = Pokemon(pokedex.getValue("Venusaur"), level = 50)
    val blastoise = Pokemon(pokedex.getValue("Blastoise"), level = 50)
    val togekiss = Pokemon(pokedex.getValue("Togekiss"), level = 50)

    val side1MovePools: Map<String, List<Move>> =
        mapOf(
            "Charizard" to listOf(MoveDex.FLAMETHROWER, MoveDex.THUNDERBOLT, MoveDex.EARTHQUAKE, MoveDex.U_TURN),
            "Garchomp" to listOf(MoveDex.EARTHQUAKE, MoveDex.ICE_BEAM, MoveDex.FLAMETHROWER, MoveDex.SWORDS_DANCE),
            "Lucario" to listOf(MoveDex.AURA_SPHERE, MoveDex.ICE_BEAM, MoveDex.THUNDERBOLT, MoveDex.SWORDS_DANCE),
        )
    val side2MovePools: Map<String, List<Move>> =
        mapOf(
            "Venusaur" to listOf(MoveDex.SLUDGE_BOMB, MoveDex.EARTHQUAKE, MoveDex.ICE_BEAM, MoveDex.GROWL),
            "Blastoise" to listOf(MoveDex.ICE_BEAM, MoveDex.EARTHQUAKE, MoveDex.AURA_SPHERE, MoveDex.SLUDGE_BOMB),
            "Togekiss" to listOf(MoveDex.AURA_SPHERE, MoveDex.ICE_BEAM, MoveDex.THUNDERBOLT, MoveDex.FLAMETHROWER),
        )

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

    val human = HumanChoiceProvider(Side.SIDE_1, side1MovePools)
    val ai = TypeAI(side2MovePools)
    val providers =
        SidedAI(
            side1 = SideProviders(human, human, human),
            side2 = SideProviders(ai, ai, ai),
        )

    val pipeline =
        TurnPipeline(
            listOf(
                MoveOrderPhase(),
                SwitchPhase(),
                MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false }),
                EndOfTurnPhase(),
            ),
        )

    playBattle(pipeline, providers, initialState)
}

private fun playBattle(
    pipeline: TurnPipeline,
    providers: SidedAI,
    initialState: BattleState,
) {
    var state = initialState
    var turn = 0
    while (turn < MAX_TURNS) {
        turn++
        println("\n=== Turn $turn ===")
        val choices = providers.getChoices(state)
        val preTurnState = state
        val result = resolveTurnWithPauses(pipeline, state, choices, providers, preTurnState)
        state = result.state.copy(turn = result.state.turn + 1)

        val preReplacementState = state
        val (afterReplacement, replacementEvents) = handleFaintReplacements(state, providers)
        state = afterReplacement
        if (replacementEvents.isNotEmpty()) {
            renderEvents(replacementEvents, preReplacementState)
        }

        when {
            state.isDefeated(Side.SIDE_1) && state.isDefeated(Side.SIDE_2) -> {
                println("\nDraw.")
                return
            }
            state.isDefeated(Side.SIDE_1) -> {
                println("\nOpponent wins.")
                return
            }
            state.isDefeated(Side.SIDE_2) -> {
                println("\nYou win!")
                return
            }
        }
    }
    println("\nTurn limit reached.")
}

/**
 * Drive a turn through mid-turn pauses by asking the [responder] for each pending
 * input. Renders progress events as they accumulate so the human sees damage land
 * before being asked to pick a switch target.
 */
private fun resolveTurnWithPauses(
    pipeline: TurnPipeline,
    initialState: BattleState,
    choices: com.pokemon.battle.engine.TurnChoices,
    responder: com.pokemon.battle.loop.InputResponder,
    preTurnState: BattleState,
): com.pokemon.battle.engine.TurnResolution.Completed {
    var resolution: com.pokemon.battle.engine.TurnResolution = pipeline.resolve(initialState, choices)
    var renderedSoFar = 0
    var renderState = preTurnState
    while (resolution is com.pokemon.battle.engine.TurnResolution.NeedInput) {
        // Show events accumulated since last render so the human sees damage land
        // before being asked to pick a replacement.
        val partial = resolution.state.partialTurnEvents
        renderState = renderSpan(partial.drop(renderedSoFar), renderState)
        renderedSoFar = partial.size

        val request =
            resolution.state.pendingInput
                ?: error("NeedInput without pendingInput — engine bug")
        val response = responder.respond(resolution.state.battle, request)
        resolution = pipeline.resume(resolution.state, choices, response)
    }
    val completed = resolution as com.pokemon.battle.engine.TurnResolution.Completed
    // Render the post-pause tail (or the whole turn if there were no pauses).
    renderSpan(completed.events.drop(renderedSoFar), renderState)
    return completed
}

/**
 * Render a span of events from [stateBefore], returning the state after applying them.
 * Filters to [com.pokemon.battle.engine.GameEvent]s — control events (pause/resume)
 * are not rendered.
 */
private fun renderSpan(
    events: List<com.pokemon.battle.engine.BattleEvent>,
    stateBefore: BattleState,
): BattleState {
    var s = stateBefore
    for (event in events.filterIsInstance<com.pokemon.battle.engine.GameEvent>()) {
        val after = event.apply(s)
        TextRenderer.render(event, s, after).forEach(::println)
        s = after
    }
    return s
}

private fun renderEvents(
    events: List<com.pokemon.battle.engine.BattleEvent>,
    stateBefore: BattleState,
) {
    var s = stateBefore
    for (event in events.filterIsInstance<com.pokemon.battle.engine.GameEvent>()) {
        val after = event.apply(s)
        TextRenderer.render(event, s, after).forEach(::println)
        s = after
    }
}

private fun handleFaintReplacements(
    startState: BattleState,
    providers: SidedAI,
): Pair<BattleState, List<com.pokemon.battle.engine.BattleEvent>> {
    var currentState = startState
    val events = mutableListOf<com.pokemon.battle.engine.BattleEvent>()
    for (slot in currentState.allSlots()) {
        val pokemon = currentState.pokemonFor(slot)
        if (!pokemon.isFainted) continue
        val bench = currentState.benchFor(slot.side)
        if (bench.isEmpty() || bench.all { it.isFainted }) continue

        val benchIndex = providers.getReplacement(currentState, slot)
        val switchIn = SwitchIn(slot, benchIndex)
        events.add(switchIn)
        currentState = switchIn.apply(currentState)
        for (abilityEvent in resolveSwitchInAbility(currentState, slot)) {
            events.add(abilityEvent)
            currentState = abilityEvent.apply(currentState)
        }
    }
    return currentState to events
}
