package com.pokemon.battle.cli

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.InputRequest
import com.pokemon.battle.engine.InputResponse
import com.pokemon.battle.engine.SwitchTargetRequest
import com.pokemon.battle.engine.SwitchTargetResponse
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.loop.ChoiceProvider
import com.pokemon.battle.loop.FaintReplacementProvider
import com.pokemon.battle.loop.InputResponder
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Slot

/**
 * Reads move and switch decisions from stdin for one side of a battle. Pairs
 * with another [ChoiceProvider] (typically an AI) via [com.pokemon.battle.ai.SidedAI].
 *
 * Design note (diary 056): prompts are numbered, one decision per line, minimal
 * flourish — predictable for humans and incidentally trivial for agents to drive.
 *
 * For self-switch moves (U-turn, Volt Switch), the switch target is NOT pre-asked;
 * the engine pauses mid-turn after damage lands and calls [respond] (diary 055
 * Phase 2). The human picks the replacement with full knowledge of damage dealt.
 */
class HumanChoiceProvider(
    private val side: Side,
    private val movePools: Map<String, List<Move>>,
    private val input: () -> String? = ::readLine,
    private val output: (String) -> Unit = ::println,
) : ChoiceProvider, FaintReplacementProvider, InputResponder {
    override fun getChoices(state: BattleState): TurnChoices {
        val choices = mutableMapOf<Slot, TurnChoice>()
        for (slot in state.slotsForSide(side)) {
            val pokemon = state.pokemonFor(slot)
            if (pokemon.isFainted) continue
            choices[slot] = askActionFor(state, slot)
        }
        return TurnChoices(choices)
    }

    override fun getReplacement(
        state: BattleState,
        faintedSlot: Slot,
    ): Int {
        val bench = state.benchFor(faintedSlot.side)
        output("")
        output("${pronoun()} ${state.pokemonFor(faintedSlot).pokemon.species.name} fainted. Choose a replacement:")
        val eligible = bench.withIndex().filter { !it.value.isFainted }
        eligible.forEach { (i, p) ->
            output("  ${i + 1}. ${p.pokemon.species.name} (${p.currentHp}/${p.pokemon.maxHp} HP)")
        }
        while (true) {
            val raw = promptLine("> ")
            val idx = raw.toIntOrNull()?.minus(1)
            if (idx != null && idx in bench.indices && !bench[idx].isFainted) return idx
            output("Invalid choice. Pick a numbered bench slot above.")
        }
    }

    private fun askActionFor(
        state: BattleState,
        slot: Slot,
    ): TurnChoice {
        val active = state.pokemonFor(slot)
        val moves = movePools[active.pokemon.species.name] ?: emptyList()
        val bench = state.benchFor(slot.side)
        val eligibleBench = bench.withIndex().filter { !it.value.isFainted }

        renderSituation(state, slot)
        output("Choose an action for ${active.pokemon.species.name}:")
        moves.forEachIndexed { i, m ->
            output("  ${i + 1}. ${m.name}${moveDetail(m)}")
        }
        eligibleBench.forEach { (benchIndex, p) ->
            val menuIndex = moves.size + eligibleBench.indexOfFirst { it.index == benchIndex } + 1
            output("  $menuIndex. Switch to ${p.pokemon.species.name} (${p.currentHp}/${p.pokemon.maxHp} HP)")
        }

        while (true) {
            val raw = promptLine("> ")
            val n =
                raw.toIntOrNull() ?: run {
                    output("Enter a number.")
                    continue
                }
            if (n in 1..moves.size) {
                return TurnChoice.UseMove(moves[n - 1])
            }
            val benchMenuOffset = n - moves.size - 1
            if (benchMenuOffset in eligibleBench.indices) {
                return TurnChoice.Switch(eligibleBench[benchMenuOffset].index)
            }
            output("Pick a numbered option above.")
        }
    }

    override fun respond(
        state: BattleState,
        request: InputRequest,
    ): InputResponse =
        when (request) {
            is SwitchTargetRequest -> promptSelfSwitchTarget(state, request)
        }

    private fun promptSelfSwitchTarget(
        state: BattleState,
        request: SwitchTargetRequest,
    ): SwitchTargetResponse {
        val bench = state.benchFor(request.userSlot.side)
        val user = state.pokemonFor(request.userSlot)
        output("")
        output("${user.pokemon.species.name}'s move landed — pick a replacement:")
        request.eligibleBenchIndices.forEachIndexed { i, benchIndex ->
            val p = bench[benchIndex]
            output("  ${i + 1}. ${p.pokemon.species.name} (${p.currentHp}/${p.pokemon.maxHp} HP)")
        }
        while (true) {
            val raw = promptLine("> ")
            val n = raw.toIntOrNull()
            if (n != null && n in 1..request.eligibleBenchIndices.size) {
                return SwitchTargetResponse(benchIndex = request.eligibleBenchIndices[n - 1])
            }
            output("Pick a numbered option above.")
        }
    }

    private fun renderSituation(
        state: BattleState,
        slot: Slot,
    ) {
        output("")
        val yours = state.pokemonFor(slot)
        output("Your ${yours.pokemon.species.name}: ${yours.currentHp}/${yours.pokemon.maxHp} HP")
        for (opp in state.opponentSlots(slot)) {
            val p = state.pokemonFor(opp)
            if (p.isFainted) continue
            output("Opposing ${p.pokemon.species.name}: ${p.currentHp}/${p.pokemon.maxHp} HP")
        }
    }

    private fun moveDetail(move: Move): String {
        val power = if (move.power > 0) "pow ${move.power}" else "status"
        return " (${move.type} • $power)"
    }

    private fun pronoun() = if (side == Side.SIDE_1) "Your" else "Opposing"

    private fun promptLine(prompt: String): String {
        output(prompt)
        return input() ?: error("Unexpected end of input while waiting for a choice.")
    }
}
