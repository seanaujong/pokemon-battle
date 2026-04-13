package com.pokemon.battle.engine

import com.pokemon.battle.model.*

sealed interface BattleEvent {
    fun apply(state: BattleState): BattleState
}

data class MoveOrderDecided(
    val order: List<Slot>,
    val reason: OrderReason
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state // informational
}

data class MoveAttempted(
    val attacker: Slot,
    val move: Move
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state // informational
}

data class MoveFailed(
    val attacker: Slot,
    val reason: FailReason
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state // informational
}

data class DamageDealt(
    val target: Slot,
    val amount: Int,
    val effectiveness: Effectiveness,
    val critical: Boolean
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp - amount).coerceAtLeast(0)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}

data class PokemonFainted(
    val slot: Slot
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state // HP already at 0 from DamageDealt
}

data class StatusApplied(
    val target: Slot,
    val status: StatusCondition
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        return state.withPokemon(target, pokemon.copy(status = status))
    }
}

data class StatusDamage(
    val target: Slot,
    val amount: Int,
    val source: StatusCondition
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp - amount).coerceAtLeast(0)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}

data class WeatherDamage(
    val target: Slot,
    val amount: Int,
    val weather: Weather
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp - amount).coerceAtLeast(0)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}

data class ItemHealing(
    val target: Slot,
    val amount: Int,
    val item: Item
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newHp = (pokemon.currentHp + amount).coerceAtMost(pokemon.maxHp)
        return state.withPokemon(target, pokemon.copy(currentHp = newHp))
    }
}

data class WeatherTick(
    val weather: Weather,
    val turnsRemaining: Int
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val newField = if (turnsRemaining <= 0) {
            state.field.copy(weather = null, weatherTurnsRemaining = 0)
        } else {
            state.field.copy(weatherTurnsRemaining = turnsRemaining)
        }
        return state.copy(field = newField)
    }
}

data class StatChanged(
    val target: Slot,
    val stat: StatType,
    val stages: Int
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        return state.withPokemon(target, pokemon.copy(statStages = pokemon.statStages.withChange(stat, stages)))
    }
}

data class VolatileChanged(
    val target: Slot,
    val old: Volatile,
    val new: Volatile?
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val without = pokemon.volatiles.filterNot { it == old }.toSet()
        val newVolatiles = if (new != null) without + new else without
        return state.withPokemon(target, pokemon.copy(volatiles = newVolatiles))
    }
}

data class StatusCleared(
    val target: Slot,
    val status: StatusCondition
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(target)
        val newVolatiles = when (status) {
            StatusCondition.SLEEP -> pokemon.volatiles.filterNot { it is Volatile.Sleep }.toSet()
            else -> pokemon.volatiles
        }
        return state.withPokemon(target, pokemon.copy(status = null, volatiles = newVolatiles))
    }
}

data class SwitchOut(
    val slot: Slot
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(slot)
        // Clear volatiles and stat stages on switch-out; status persists
        val cleared = pokemon.copy(volatiles = emptySet(), statStages = StatStages())
        val side = slot.side
        val newBench = state.benchFor(side) + cleared
        return state
            .withPokemon(slot, cleared)
            .copy(bench = state.bench + (side to newBench))
    }
}

data class SwitchIn(
    val slot: Slot,
    val benchIndex: Int
) : BattleEvent {
    override fun apply(state: BattleState): BattleState {
        val side = slot.side
        val bench = state.benchFor(side)
        val incoming = bench[benchIndex]
        val newBench = bench.toMutableList().apply { removeAt(benchIndex) }
        return state
            .withPokemon(slot, incoming)
            .copy(bench = state.bench + (side to newBench))
    }
}
