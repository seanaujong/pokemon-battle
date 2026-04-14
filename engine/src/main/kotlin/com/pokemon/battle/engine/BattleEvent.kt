package com.pokemon.battle.engine

import com.pokemon.battle.model.Effectiveness
import com.pokemon.battle.model.FailReason
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.OrderReason
import com.pokemon.battle.model.Slot
import kotlinx.serialization.Serializable

@Serializable
sealed interface BattleEvent {
    fun apply(state: BattleState): BattleState
}

// --- Core move events ---

@Serializable
data class MoveOrderDecided(
    val order: List<Slot>,
    val leadReason: OrderReason,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state
}

@Serializable
data class MoveAttempted(
    val attacker: Slot,
    val move: Move,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state
}

@Serializable
data class MoveFailed(
    val attacker: Slot,
    val reason: FailReason,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state
}

@Serializable
data class DamageDealt(
    val target: Slot,
    val amount: Int,
    val effectiveness: Effectiveness,
    val critical: Boolean,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp - amount).coerceAtLeast(0)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}

@Serializable
data class PokemonFainted(
    val slot: Slot,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state
}

@Serializable
data class ProtectBlocked(
    val slot: Slot,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state
}
