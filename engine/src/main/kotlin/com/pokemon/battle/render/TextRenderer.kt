package com.pokemon.battle.render

import com.pokemon.battle.engine.AbilityBlocked
import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.GimmickUsed
import com.pokemon.battle.engine.HazardDamage
import com.pokemon.battle.engine.HazardRemoved
import com.pokemon.battle.engine.HazardSet
import com.pokemon.battle.engine.ItemConsumed
import com.pokemon.battle.engine.ItemDamage
import com.pokemon.battle.engine.ItemHealing
import com.pokemon.battle.engine.MoveAttempted
import com.pokemon.battle.engine.MoveFailed
import com.pokemon.battle.engine.MoveOrderDecided
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.ProtectBlocked
import com.pokemon.battle.engine.SideConditionExpired
import com.pokemon.battle.engine.SideConditionSet
import com.pokemon.battle.engine.SideConditionTick
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.engine.StatusApplied
import com.pokemon.battle.engine.StatusCleared
import com.pokemon.battle.engine.StatusDamage
import com.pokemon.battle.engine.SwitchIn
import com.pokemon.battle.engine.SwitchOut
import com.pokemon.battle.engine.TrickRoomSet
import com.pokemon.battle.engine.TypeChanged
import com.pokemon.battle.engine.VolatileAdded
import com.pokemon.battle.engine.VolatileRemoved
import com.pokemon.battle.engine.WeatherDamage
import com.pokemon.battle.engine.WeatherSet
import com.pokemon.battle.engine.WeatherTick
import com.pokemon.battle.model.Effectiveness
import com.pokemon.battle.model.FailReason
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatType
import com.pokemon.battle.model.StatusCondition
import com.pokemon.battle.model.Weather
import com.pokemon.battle.render.ability.AbilityTextRegistry
import com.pokemon.battle.render.item.ItemTextRegistry

/** Renders battle events as game-style text messages. */
@Suppress("TooManyFunctions") // One render function per event type — inherently many
object TextRenderer : BattleRenderer {
    @Suppress("CyclomaticComplexMethod") // Exhaustive when over all event types — not complex, just thorough
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
            is ProtectBlocked -> renderProtectBlocked(event, stateBefore)
            is StatChanged -> renderStatChanged(event, stateBefore)
            is StatusApplied -> renderStatusApplied(event, stateBefore)
            is StatusCleared -> renderStatusCleared(event, stateBefore)
            is StatusDamage -> renderStatusDamage(event, stateBefore)
            is WeatherDamage -> renderWeatherDamage(event, stateBefore)
            is WeatherTick -> renderWeatherTick(event)
            is WeatherSet -> renderWeatherSet(event)
            is ItemHealing -> renderItemHealing(event, stateBefore)
            is ItemConsumed -> renderItemConsumed(event, stateBefore)
            is ItemDamage -> renderItemDamage(event, stateBefore)
            is SwitchOut -> renderSwitchOut(event, stateBefore)
            is SwitchIn -> renderSwitchIn(event, stateAfter)
            is AbilityTriggered -> renderAbilityTriggered(event, stateAfter)
            is AbilityBlocked -> renderAbilityBlocked(event, stateBefore)
            is TypeChanged -> renderTypeChanged(event, stateAfter)
            is VolatileAdded -> emptyList()
            is VolatileRemoved -> emptyList()
            is SideConditionSet -> renderSideConditionSet(event)
            is SideConditionTick -> emptyList()
            is SideConditionExpired -> renderSideConditionExpired(event)
            is TrickRoomSet -> renderTrickRoomSet(event)
            is GimmickUsed -> renderGimmickUsed(event, stateAfter)
            is HazardSet -> renderHazardSet(event)
            is HazardRemoved -> renderHazardRemoved(event)
            is HazardDamage -> renderHazardDamage(event, stateBefore)
        }
    }

    private fun renderHazardSet(event: HazardSet): List<String> {
        val text =
            when (event.hazard) {
                com.pokemon.battle.model.SideHazard.STEALTH_ROCK ->
                    "Pointed stones float in the air around the opposing team!"
                com.pokemon.battle.model.SideHazard.SPIKES ->
                    "Spikes were scattered on the ground around the opposing team! (${event.layers})"
                com.pokemon.battle.model.SideHazard.TOXIC_SPIKES ->
                    "Poison spikes were scattered around the opposing team! (${event.layers})"
                com.pokemon.battle.model.SideHazard.STICKY_WEB ->
                    "A sticky web was laid around the opposing team!"
            }
        return listOf(text)
    }

    private fun renderHazardRemoved(event: HazardRemoved): List<String> =
        listOf(
            when (event.hazard) {
                com.pokemon.battle.model.SideHazard.STEALTH_ROCK -> "The pointed stones disappeared."
                com.pokemon.battle.model.SideHazard.SPIKES -> "The spikes were cleared."
                com.pokemon.battle.model.SideHazard.TOXIC_SPIKES -> "The toxic spikes were absorbed."
                com.pokemon.battle.model.SideHazard.STICKY_WEB -> "The sticky web was swept away."
            },
        )

    private fun renderHazardDamage(
        event: HazardDamage,
        state: BattleState,
    ): List<String> {
        val pokemonName = name(state, event.target)
        return listOf(
            when (event.hazard) {
                com.pokemon.battle.model.SideHazard.STEALTH_ROCK -> "Pointed stones dug into $pokemonName!"
                com.pokemon.battle.model.SideHazard.SPIKES -> "$pokemonName is hurt by the spikes!"
                else -> "$pokemonName was hurt by hazard!"
            },
        )
    }

    private fun renderGimmickUsed(
        event: GimmickUsed,
        stateAfter: BattleState,
    ): List<String> {
        val pokemonName = name(stateAfter, event.slot)
        val text =
            when (event.kind) {
                com.pokemon.battle.model.GimmickKind.MEGA -> "$pokemonName Mega Evolved!"
                com.pokemon.battle.model.GimmickKind.Z_MOVE -> "$pokemonName unleashed its Z-Power!"
                com.pokemon.battle.model.GimmickKind.DYNAMAX -> "$pokemonName Dynamaxed!"
                com.pokemon.battle.model.GimmickKind.TERA -> "$pokemonName Terastallized!"
            }
        return listOf(text)
    }

    private fun renderTrickRoomSet(event: TrickRoomSet): List<String> =
        listOf(
            if (event.turnsRemaining > 0) "The dimensions twisted — Trick Room is up!" else "The twisted dimensions returned to normal.",
        )

    private fun renderSideConditionSet(event: SideConditionSet): List<String> {
        val sideLabel = if (event.side.name == "SIDE_1") "Player 1" else "Player 2"
        val text =
            when (event.condition) {
                com.pokemon.battle.model.SideCondition.TAILWIND -> "A tailwind kicked up on $sideLabel's side!"
            }
        return listOf(text)
    }

    private fun renderSideConditionExpired(event: SideConditionExpired): List<String> {
        val sideLabel = if (event.side.name == "SIDE_1") "Player 1" else "Player 2"
        val text =
            when (event.condition) {
                com.pokemon.battle.model.SideCondition.TAILWIND -> "The tailwind on $sideLabel's side died down."
            }
        return listOf(text)
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
                FailReason.PROTECT_FAILED -> "But it failed!"
                FailReason.NOT_FIRST_TURN -> "But it failed!"
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

    private fun renderProtectBlocked(
        event: ProtectBlocked,
        state: BattleState,
    ): List<String> = listOf("${name(state, event.slot)} protected itself!")

    // --- Stats ---

    @Suppress("CyclomaticComplexMethod") // Maps stage ranges to phrasing tiers
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
        val text = ItemTextRegistry.textFor(event.item)?.renderHealing(event.amount, name(state, event.target))
        return if (text.isNullOrEmpty()) emptyList() else listOf(text)
    }

    private fun renderItemConsumed(
        event: ItemConsumed,
        state: BattleState,
    ): List<String> {
        val text = ItemTextRegistry.textFor(event.item)?.renderConsumed(name(state, event.target))
        return if (text.isNullOrEmpty()) emptyList() else listOf(text)
    }

    private fun renderItemDamage(
        event: ItemDamage,
        state: BattleState,
    ): List<String> {
        val text = ItemTextRegistry.textFor(event.item)?.renderDamage(event.amount, name(state, event.target))
        return if (text.isNullOrEmpty()) emptyList() else listOf(text)
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

    private fun renderAbilityTriggered(
        event: AbilityTriggered,
        stateAfter: BattleState,
    ): List<String> {
        val pokemonName = name(stateAfter, event.slot)
        return listOf(AbilityTextRegistry.textFor(event.ability).renderTriggered(pokemonName))
    }

    private fun renderAbilityBlocked(
        event: AbilityBlocked,
        state: BattleState,
    ): List<String> {
        val pokemonName = name(state, event.slot)
        return listOf(AbilityTextRegistry.textFor(event.ability).renderBlocked(pokemonName))
    }

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
