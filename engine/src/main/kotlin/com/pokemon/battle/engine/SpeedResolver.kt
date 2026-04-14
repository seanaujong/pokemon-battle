package com.pokemon.battle.engine

import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.SideCondition
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatusCondition

fun interface SpeedResolver {
    /**
     * [state] is passed so resolvers can account for field-level (Trick Room, Gravity)
     * and side-level (Tailwind, Swamp) effects on top of the Pokemon's own modifiers.
     */
    fun effectiveSpeed(
        pokemon: PokemonState,
        slot: Slot,
        state: BattleState,
    ): Double
}

/**
 * Gen V+ speed = base * stage * paralysis-penalty * item-mod * ability-mod * tailwind-mod.
 * Trick Room doesn't modify the speed value — it inverts the sort order in
 * [resolveMoveOrder]. Keeping speeds positive keeps the calculation composable.
 *
 * Diary 071 converted this from a top-level `val` into a factory that takes
 * [Registries] so items and abilities could move to `:data`.
 */
internal fun genVSpeedResolver(registries: Registries): SpeedResolver =
    SpeedResolver { pokemon, slot, state ->
        val base = pokemon.baseEffectiveSpeed()
        val paralysisMod = if (pokemon.status == StatusCondition.PARALYSIS) 0.5 else 1.0
        val itemMod = registries.items.effectForHolder(pokemon)?.speedModifier(pokemon) ?: 1.0
        val abilityMod = registries.abilities.effectFor(pokemon.effectiveAbility)?.speedModifier(pokemon) ?: 1.0
        val tailwindMod = if (state.sideConditionsFor(slot.side).containsKey(SideCondition.TAILWIND)) 2.0 else 1.0
        base * paralysisMod * itemMod * abilityMod * tailwindMod
    }
