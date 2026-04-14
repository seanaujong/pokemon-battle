package com.pokemon.battle.engine

import com.pokemon.battle.model.Effectiveness
import com.pokemon.battle.model.FailReason
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.OrderReason
import com.pokemon.battle.model.Slot
import kotlinx.serialization.Serializable

/**
 * Marker for everything that goes in the per-turn event log. Two sub-hierarchies
 * (see diary 061):
 *
 * - [GameEvent] — things that happened in the battle (damage, switches, status). These
 *   transform [BattleState]; the renderer only sees these.
 * - [ControlEvent] — pipeline state transitions (pause for input, resume). These
 *   transform [PipelineState]; renderers and game-logic consumers ignore them.
 */
@Serializable
sealed interface BattleEvent

/**
 * An in-battle event with game-meaningful semantics. Mutates [BattleState] via [apply].
 */
@Serializable
sealed interface GameEvent : BattleEvent {
    fun apply(state: BattleState): BattleState
}

/**
 * A pipeline-machinery event (pause, resume). Mutates [PipelineState] via [apply].
 * Not rendered; not part of the game's mechanical narrative.
 */
@Serializable
sealed interface ControlEvent : BattleEvent {
    fun apply(state: PipelineState): PipelineState
}

/**
 * Apply any [BattleEvent] to a [PipelineState], dispatching by sub-hierarchy.
 * Game events wrap-and-update [PipelineState.battle]; control events update the
 * pipeline-state directly.
 */
fun BattleEvent.applyTo(state: PipelineState): PipelineState =
    when (this) {
        is GameEvent -> state.copy(battle = apply(state.battle))
        is ControlEvent -> apply(state)
    }

// --- Core move events ---

@Serializable
data class MoveOrderDecided(
    val order: List<Slot>,
    val leadReason: OrderReason,
) : GameEvent {
    override fun apply(state: BattleState): BattleState = state
}

@Serializable
data class MoveAttempted(
    val attacker: Slot,
    val move: Move,
) : GameEvent {
    override fun apply(state: BattleState): BattleState = state
}

@Serializable
data class MoveFailed(
    val attacker: Slot,
    val reason: FailReason,
) : GameEvent {
    override fun apply(state: BattleState): BattleState = state
}

@Serializable
data class DamageDealt(
    val target: Slot,
    val amount: Int,
    val effectiveness: Effectiveness,
    val critical: Boolean,
) : GameEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp - amount).coerceAtLeast(0)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}

@Serializable
data class PokemonFainted(
    val slot: Slot,
) : GameEvent {
    override fun apply(state: BattleState): BattleState = state
}

@Serializable
data class ProtectBlocked(
    val slot: Slot,
) : GameEvent {
    override fun apply(state: BattleState): BattleState = state
}
