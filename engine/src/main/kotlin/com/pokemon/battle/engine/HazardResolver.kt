package com.pokemon.battle.engine

import com.pokemon.battle.engine.item.ItemRegistry
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.SideHazard
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatType
import com.pokemon.battle.model.StatusCondition
import com.pokemon.battle.model.Type

/**
 * Fires all entry hazards on [slot]'s side that apply to the freshly-switched-in Pokemon.
 * Called from [com.pokemon.battle.phase.SwitchPhase], self-switch resolution, and faint
 * replacement — anywhere a [SwitchIn] event has just been applied.
 *
 * Order (mainline): Stealth Rock → Spikes → Toxic Spikes → Sticky Web. A Pokemon that
 * faints from an earlier hazard doesn't trigger later ones.
 */
fun resolveHazardsOnSwitchIn(
    state: BattleState,
    slot: Slot,
): List<BattleEvent> {
    val hazards = state.hazardsOn(slot.side)
    if (hazards.isEmpty()) return emptyList()
    if (bypassesHazards(state.pokemonFor(slot))) return emptyList()
    return applyHazardsInOrder(state, slot, hazards)
}

/**
 * Whether [pokemon] ignores entry hazards on switch-in. Today: Heavy-Duty
 * Boots (via [ItemRegistry.effectForHolder] so Klutz suppression applies).
 */
private fun bypassesHazards(pokemon: PokemonState): Boolean = ItemRegistry.effectForHolder(pokemon)?.blocksHazards(pokemon) == true

private fun applyHazardsInOrder(
    state: BattleState,
    slot: Slot,
    hazards: Map<SideHazard, Int>,
): List<BattleEvent> {
    val events = mutableListOf<BattleEvent>()
    var currentState = state

    fun applyAll(hazardEvents: List<BattleEvent>) {
        events.addAll(hazardEvents)
        currentState = hazardEvents.fold(currentState) { s, e -> e.apply(s) }
    }

    // Stealth Rock — type-based, not grounded-gated (everyone takes it)
    hazards[SideHazard.STEALTH_ROCK]?.let {
        val pokemon = currentState.pokemonFor(slot)
        if (!pokemon.isFainted) applyAll(stealthRockEvents(pokemon, slot))
    }

    // Spikes — grounded only, layer-count determines damage
    hazards[SideHazard.SPIKES]?.let { layers ->
        val pokemon = currentState.pokemonFor(slot)
        if (!pokemon.isFainted && pokemon.isGrounded) applyAll(spikesEvents(pokemon, slot, layers))
    }

    // Toxic Spikes — grounded only; Poison types absorb, Steel ignores
    hazards[SideHazard.TOXIC_SPIKES]?.let { layers ->
        val pokemon = currentState.pokemonFor(slot)
        if (!pokemon.isFainted && pokemon.isGrounded) applyAll(toxicSpikesEvents(pokemon, slot, layers))
    }

    // Sticky Web — grounded only; no damage, just Speed drop
    hazards[SideHazard.STICKY_WEB]?.let {
        val pokemon = currentState.pokemonFor(slot)
        if (!pokemon.isFainted && pokemon.isGrounded) {
            events.add(StatChanged(slot, StatType.SPEED, -1))
        }
    }

    return events
}

private fun stealthRockEvents(
    pokemon: PokemonState,
    slot: Slot,
): List<BattleEvent> {
    val rockEff = StandardTypeChart.effectiveness(Type.ROCK, pokemon.effectiveTypes)
    if (rockEff == 0.0) return emptyList() // Pokemon fully immune to Rock
    val damage = (pokemon.maxHp * rockEff / STEALTH_ROCK_DIVISOR).toInt().coerceAtLeast(1)
    val events = mutableListOf<BattleEvent>(HazardDamage(slot, damage, SideHazard.STEALTH_ROCK))
    if (pokemon.currentHp <= damage) events.add(PokemonFainted(slot))
    return events
}

private fun spikesEvents(
    pokemon: PokemonState,
    slot: Slot,
    layers: Int,
): List<BattleEvent> {
    val damage =
        when (layers.coerceIn(1, SPIKES_MAX_LAYERS)) {
            1 -> pokemon.maxHp / SPIKES_1L_DIVISOR
            2 -> pokemon.maxHp / SPIKES_2L_DIVISOR
            else -> pokemon.maxHp / SPIKES_3L_DIVISOR
        }.coerceAtLeast(1)
    val events = mutableListOf<BattleEvent>(HazardDamage(slot, damage, SideHazard.SPIKES))
    if (pokemon.currentHp <= damage) events.add(PokemonFainted(slot))
    return events
}

private fun toxicSpikesEvents(
    pokemon: PokemonState,
    slot: Slot,
    layers: Int,
): List<BattleEvent> {
    if (Type.STEEL in pokemon.effectiveTypes) return emptyList()
    if (Type.POISON in pokemon.effectiveTypes) {
        // Grounded Poison type absorbs all layers.
        return listOf(HazardRemoved(slot.side, SideHazard.TOXIC_SPIKES))
    }
    if (pokemon.status != null) return emptyList() // already statused — silent no-op
    // FIDELITY NOTE: real Toxic Spikes at 2 layers applies badly-poisoned (Toxic) with
    // escalating damage. Our StatusCondition enum doesn't distinguish yet — treating
    // both as POISON. Future diary: TOXIC status + escalating damage.
    @Suppress("UNUSED_PARAMETER")
    val layersApplied = layers
    return listOf(StatusApplied(slot, StatusCondition.POISON))
}

private const val STEALTH_ROCK_DIVISOR = 8.0
private const val SPIKES_MAX_LAYERS = 3
private const val SPIKES_1L_DIVISOR = 8
private const val SPIKES_2L_DIVISOR = 6
private const val SPIKES_3L_DIVISOR = 4
