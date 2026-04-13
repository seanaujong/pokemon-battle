package com.pokemon.battle.render

import com.pokemon.battle.engine.AbilityBlocked
import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.ItemHealing
import com.pokemon.battle.engine.MoveAttempted
import com.pokemon.battle.engine.MoveFailed
import com.pokemon.battle.engine.MoveOrderDecided
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.engine.StatusApplied
import com.pokemon.battle.engine.StatusCleared
import com.pokemon.battle.engine.StatusDamage
import com.pokemon.battle.engine.SwitchIn
import com.pokemon.battle.engine.SwitchOut
import com.pokemon.battle.engine.TypeChanged
import com.pokemon.battle.engine.VolatileChanged
import com.pokemon.battle.engine.WeatherDamage
import com.pokemon.battle.engine.WeatherSet
import com.pokemon.battle.engine.WeatherTick
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Effectiveness
import com.pokemon.battle.model.FailReason
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatType
import com.pokemon.battle.model.StatusCondition
import com.pokemon.battle.model.Weather

/** Renders battle events as game-style text messages. */
object TextRenderer : BattleRenderer {
    override fun render(
        event: BattleEvent,
        stateBefore: BattleState,
        stateAfter: BattleState,
    ): List<String> {
        return when (event) {
            is MoveOrderDecided -> emptyList()
            is MoveAttempted -> renderMoveAttempted(event, stateBefore)
            is MoveFailed -> renderMoveFailed(event, stateBefore)
            is DamageDealt -> renderDamageDealt(event, stateBefore, stateAfter)
            is PokemonFainted -> renderFainted(event, stateBefore)
            is StatChanged -> renderStatChanged(event, stateBefore)
            is StatusApplied -> renderStatusApplied(event, stateBefore)
            is StatusCleared -> renderStatusCleared(event, stateBefore)
            is StatusDamage -> renderStatusDamage(event, stateBefore)
            is WeatherDamage -> renderWeatherDamage(event, stateBefore)
            is WeatherTick -> renderWeatherTick(event)
            is WeatherSet -> renderWeatherSet(event)
            is ItemHealing -> renderItemHealing(event, stateBefore)
            is SwitchOut -> renderSwitchOut(event, stateBefore)
            is SwitchIn -> renderSwitchIn(event, stateAfter)
            is AbilityTriggered -> renderAbilityTriggered(event, stateAfter)
            is AbilityBlocked -> renderAbilityBlocked(event, stateBefore)
            is TypeChanged -> renderTypeChanged(event, stateAfter)
            is VolatileChanged -> emptyList()
        }
    }

    private fun name(
        state: BattleState,
        slot: Slot,
    ): String = state.pokemonFor(slot).pokemon.species.name

    // --- Move events ---

    private fun renderMoveAttempted(
        event: MoveAttempted,
        state: BattleState,
    ): List<String> = listOf("${name(state, event.attacker)} used ${event.move.name}!")

    private fun renderMoveFailed(
        event: MoveFailed,
        state: BattleState,
    ): List<String> {
        val pokemonName = name(state, event.attacker)
        return listOf(
            when (event.reason) {
                FailReason.ASLEEP -> "$pokemonName is fast asleep!"
                FailReason.FROZEN -> "$pokemonName is frozen solid!"
                FailReason.FULLY_PARALYZED -> "$pokemonName is fully paralyzed!"
                FailReason.FLINCHED -> "$pokemonName flinched!"
                FailReason.CONFUSION_SELF_HIT -> "$pokemonName hurt itself in its confusion!"
            },
        )
    }

    // --- Damage ---

    private fun renderDamageDealt(
        event: DamageDealt,
        stateBefore: BattleState,
        stateAfter: BattleState,
    ): List<String> {
        val lines = mutableListOf<String>()

        when (event.effectiveness) {
            Effectiveness.SUPER_EFFECTIVE -> lines.add("It's super effective!")
            Effectiveness.NOT_VERY_EFFECTIVE -> lines.add("It's not very effective...")
            Effectiveness.IMMUNE -> lines.add("It doesn't affect ${name(stateBefore, event.target)}...")
            Effectiveness.NEUTRAL -> {}
        }

        if (event.critical) {
            lines.add("A critical hit!")
        }

        val hpBefore = stateBefore.pokemonFor(event.target).currentHp
        val hpAfter = stateAfter.pokemonFor(event.target).currentHp
        val pokemonName = name(stateBefore, event.target)
        lines.add("$pokemonName took ${event.amount} damage! ($hpBefore \u2192 $hpAfter HP)")

        return lines
    }

    private fun renderFainted(
        event: PokemonFainted,
        state: BattleState,
    ): List<String> = listOf("${name(state, event.slot)} fainted!")

    // --- Stats ---

    private fun renderStatChanged(
        event: StatChanged,
        state: BattleState,
    ): List<String> {
        // Silent for stat clearing on switch-out (negative-of-current = resetting to 0)
        // We detect this by checking if the result would be 0
        val currentStage = state.pokemonFor(event.target).statStages.forStat(event.stat)
        if (currentStage + event.stages == 0 && event.stages != 0) return emptyList()

        val pokemonName = name(state, event.target)
        val statName =
            when (event.stat) {
                StatType.ATTACK -> "Attack"
                StatType.DEFENSE -> "Defense"
                StatType.SPECIAL_ATTACK -> "Sp. Atk"
                StatType.SPECIAL_DEFENSE -> "Sp. Def"
                StatType.SPEED -> "Speed"
            }
        val change =
            when {
                event.stages >= 3 -> "rose drastically!"
                event.stages == 2 -> "rose sharply!"
                event.stages == 1 -> "rose!"
                event.stages == -1 -> "fell!"
                event.stages == -2 -> "fell harshly!"
                event.stages <= -3 -> "fell severely!"
                else -> return emptyList() // stages == 0, no change
            }
        return listOf("$pokemonName's $statName $change")
    }

    // --- Status ---

    private fun renderStatusApplied(
        event: StatusApplied,
        state: BattleState,
    ): List<String> {
        val pokemonName = name(state, event.target)
        return listOf(
            when (event.status) {
                StatusCondition.BURN -> "$pokemonName was burned!"
                StatusCondition.POISON -> "$pokemonName was poisoned!"
                StatusCondition.PARALYSIS -> "$pokemonName is paralyzed! It may be unable to move!"
                StatusCondition.SLEEP -> "$pokemonName fell asleep!"
                StatusCondition.FREEZE -> "$pokemonName was frozen solid!"
            },
        )
    }

    private fun renderStatusCleared(
        event: StatusCleared,
        state: BattleState,
    ): List<String> {
        val pokemonName = name(state, event.target)
        return listOf(
            when (event.status) {
                StatusCondition.SLEEP -> "$pokemonName woke up!"
                StatusCondition.FREEZE -> "$pokemonName thawed out!"
                StatusCondition.PARALYSIS -> "$pokemonName was cured of paralysis!"
                StatusCondition.BURN -> "$pokemonName was cured of its burn!"
                StatusCondition.POISON -> "$pokemonName was cured of poison!"
            },
        )
    }

    private fun renderStatusDamage(
        event: StatusDamage,
        state: BattleState,
    ): List<String> {
        val pokemonName = name(state, event.target)
        return listOf(
            when (event.source) {
                StatusCondition.BURN -> "$pokemonName is hurt by its burn!"
                StatusCondition.POISON -> "$pokemonName is hurt by poison!"
                else -> "$pokemonName took ${event.amount} damage from ${event.source}!"
            },
        )
    }

    // --- Weather ---

    private fun renderWeatherDamage(
        event: WeatherDamage,
        state: BattleState,
    ): List<String> {
        val pokemonName = name(state, event.target)
        return listOf(
            when (event.weather) {
                Weather.SANDSTORM -> "$pokemonName is buffeted by the sandstorm!"
                Weather.HAIL -> "$pokemonName is buffeted by the hail!"
                else -> "$pokemonName took weather damage!"
            },
        )
    }

    private fun renderWeatherTick(event: WeatherTick): List<String> {
        if (event.turnsRemaining <= 0) {
            return listOf(
                when (event.weather) {
                    Weather.SANDSTORM -> "The sandstorm subsided."
                    Weather.HAIL -> "The hail stopped."
                    Weather.SUN -> "The harsh sunlight faded."
                    Weather.RAIN -> "The rain stopped."
                },
            )
        }
        return listOf(
            when (event.weather) {
                Weather.SANDSTORM -> "The sandstorm rages."
                Weather.HAIL -> "The hail continues to fall."
                Weather.SUN -> "The sunlight is strong."
                Weather.RAIN -> "Rain continues to fall."
            },
        )
    }

    private fun renderWeatherSet(event: WeatherSet): List<String> {
        return listOf(
            when (event.weather) {
                Weather.RAIN -> "It started to rain!"
                Weather.SUN -> "The sunlight turned harsh!"
                Weather.SANDSTORM -> "A sandstorm kicked up!"
                Weather.HAIL -> "It started to hail!"
            },
        )
    }

    // --- Items ---

    private fun renderItemHealing(
        event: ItemHealing,
        state: BattleState,
    ): List<String> {
        val pokemonName = name(state, event.target)
        return listOf(
            when (event.item) {
                Item.LEFTOVERS -> "$pokemonName restored a little HP using its Leftovers!"
            },
        )
    }

    // --- Switching ---

    private fun renderSwitchOut(
        event: SwitchOut,
        state: BattleState,
    ): List<String> = listOf("${name(state, event.slot)}, come back!")

    private fun renderSwitchIn(
        event: SwitchIn,
        stateAfter: BattleState,
    ): List<String> = listOf("Go! ${name(stateAfter, event.slot)}!")

    // --- Abilities ---

    private fun abilityName(ability: Ability): String = ability.name.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }

    private fun renderAbilityTriggered(
        event: AbilityTriggered,
        stateAfter: BattleState,
    ): List<String> = listOf("${name(stateAfter, event.slot)}'s ${abilityName(event.ability)}!")

    private fun renderAbilityBlocked(
        event: AbilityBlocked,
        state: BattleState,
    ): List<String> = listOf("It doesn't affect ${name(state, event.slot)}... (${abilityName(event.ability)})")

    // --- Type changes ---

    private fun renderTypeChanged(
        event: TypeChanged,
        stateAfter: BattleState,
    ): List<String> {
        val pokemonName = name(stateAfter, event.target)
        val typeNames = event.newTypes.joinToString("/") { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
        return listOf("$pokemonName's type changed to $typeNames!")
    }
}
