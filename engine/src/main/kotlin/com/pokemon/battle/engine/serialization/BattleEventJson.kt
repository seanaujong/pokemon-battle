package com.pokemon.battle.engine.serialization

import com.pokemon.battle.engine.AbilityBlocked
import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.ControlEvent
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.GimmickUsed
import com.pokemon.battle.engine.HazardDamage
import com.pokemon.battle.engine.HazardRemoved
import com.pokemon.battle.engine.HazardSet
import com.pokemon.battle.engine.InputRequest
import com.pokemon.battle.engine.InputResponse
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
import com.pokemon.battle.engine.SwitchReason
import com.pokemon.battle.engine.SwitchTargetRequest
import com.pokemon.battle.engine.SwitchTargetResponse
import com.pokemon.battle.engine.TrickRoomSet
import com.pokemon.battle.engine.TurnInputResolved
import com.pokemon.battle.engine.TurnPausedForInput
import com.pokemon.battle.engine.TypeChanged
import com.pokemon.battle.engine.VolatileAdded
import com.pokemon.battle.engine.VolatileRemoved
import com.pokemon.battle.engine.WeatherDamage
import com.pokemon.battle.engine.WeatherSet
import com.pokemon.battle.engine.WeatherTick
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Effectiveness
import com.pokemon.battle.model.FailReason
import com.pokemon.battle.model.GimmickKind
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.OrderReason
import com.pokemon.battle.model.SideCondition
import com.pokemon.battle.model.SideHazard
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatType
import com.pokemon.battle.model.StatusCondition
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.Volatile
import com.pokemon.battle.model.Weather
import kotlinx.serialization.Serializable

/**
 * On-disk / wire DTO layer for the [BattleEvent] hierarchy. See diary 060.
 *
 * Domain types (BattleEvent and subclasses) are *not* `@Serializable`; this file
 * is the only serialization contract. A rename of a domain field requires updating
 * the DTO's `toDomain` mapping but does not break stored JSON.
 *
 * Nested domain types (`Slot`, `Move`, enums) stay `@Serializable` for now — the
 * event-hierarchy decoupling is the shipping-level win. Deeper decoupling (MoveJson,
 * SlotJson, etc.) is a separate refactor if a forcing function arrives.
 */
@Serializable
sealed interface BattleEventJson {
    fun toDomain(): BattleEvent
}

@Serializable
sealed interface GameEventJson : BattleEventJson {
    override fun toDomain(): GameEvent
}

@Serializable
sealed interface ControlEventJson : BattleEventJson {
    override fun toDomain(): ControlEvent
}

// --- Core move events ---

@Serializable
data class MoveOrderDecidedJson(val order: List<Slot>, val leadReason: OrderReason) : GameEventJson {
    override fun toDomain() = MoveOrderDecided(order, leadReason)
}

@Serializable
data class MoveAttemptedJson(val attacker: Slot, val move: Move) : GameEventJson {
    override fun toDomain() = MoveAttempted(attacker, move)
}

@Serializable
data class MoveFailedJson(val attacker: Slot, val reason: FailReason) : GameEventJson {
    override fun toDomain() = MoveFailed(attacker, reason)
}

@Serializable
data class DamageDealtJson(
    val target: Slot,
    val amount: Int,
    val effectiveness: Effectiveness,
    val critical: Boolean,
) : GameEventJson {
    override fun toDomain() = DamageDealt(target, amount, effectiveness, critical)
}

@Serializable
data class PokemonFaintedJson(val slot: Slot) : GameEventJson {
    override fun toDomain() = PokemonFainted(slot)
}

@Serializable
data class ProtectBlockedJson(val slot: Slot) : GameEventJson {
    override fun toDomain() = ProtectBlocked(slot)
}

// --- Stat / volatile / type events ---

@Serializable
data class StatChangedJson(val target: Slot, val stat: StatType, val stages: Int) : GameEventJson {
    override fun toDomain() = StatChanged(target, stat, stages)
}

@Serializable
data class TypeChangedJson(val target: Slot, val newTypes: List<Type>) : GameEventJson {
    override fun toDomain() = TypeChanged(target, newTypes)
}

@Serializable
data class VolatileAddedJson(val target: Slot, val volatile: Volatile) : GameEventJson {
    override fun toDomain() = VolatileAdded(target, volatile)
}

@Serializable
data class VolatileRemovedJson(val target: Slot, val volatile: Volatile) : GameEventJson {
    override fun toDomain() = VolatileRemoved(target, volatile)
}

// --- Status events ---

@Serializable
data class StatusAppliedJson(val target: Slot, val status: StatusCondition) : GameEventJson {
    override fun toDomain() = StatusApplied(target, status)
}

@Serializable
data class StatusClearedJson(val target: Slot, val status: StatusCondition) : GameEventJson {
    override fun toDomain() = StatusCleared(target, status)
}

@Serializable
data class StatusDamageJson(val target: Slot, val amount: Int, val source: StatusCondition) : GameEventJson {
    override fun toDomain() = StatusDamage(target, amount, source)
}

// --- Weather events ---

@Serializable
data class WeatherDamageJson(val target: Slot, val amount: Int, val weather: Weather) : GameEventJson {
    override fun toDomain() = WeatherDamage(target, amount, weather)
}

@Serializable
data class WeatherTickJson(val weather: Weather, val turnsRemaining: Int) : GameEventJson {
    override fun toDomain() = WeatherTick(weather, turnsRemaining)
}

@Serializable
data class WeatherSetJson(val weather: Weather, val turnsRemaining: Int) : GameEventJson {
    override fun toDomain() = WeatherSet(weather, turnsRemaining)
}

@Serializable
data class TrickRoomSetJson(val turnsRemaining: Int) : GameEventJson {
    override fun toDomain() = TrickRoomSet(turnsRemaining)
}

// --- Item events ---

@Serializable
data class ItemHealingJson(val target: Slot, val amount: Int, val item: Item) : GameEventJson {
    override fun toDomain() = ItemHealing(target, amount, item)
}

@Serializable
data class ItemConsumedJson(val target: Slot, val item: Item) : GameEventJson {
    override fun toDomain() = ItemConsumed(target, item)
}

@Serializable
data class ItemDamageJson(val target: Slot, val amount: Int, val item: Item) : GameEventJson {
    override fun toDomain() = ItemDamage(target, amount, item)
}

// --- Switch events ---

@Serializable
data class SwitchOutJson(val slot: Slot) : GameEventJson {
    override fun toDomain() = SwitchOut(slot)
}

@Serializable
data class SwitchInJson(val slot: Slot, val benchIndex: Int) : GameEventJson {
    override fun toDomain() = SwitchIn(slot, benchIndex)
}

// --- Ability events ---

@Serializable
data class AbilityTriggeredJson(val slot: Slot, val ability: Ability) : GameEventJson {
    override fun toDomain() = AbilityTriggered(slot, ability)
}

@Serializable
data class AbilityBlockedJson(val slot: Slot, val ability: Ability) : GameEventJson {
    override fun toDomain() = AbilityBlocked(slot, ability)
}

// --- Side conditions ---

@Serializable
data class SideConditionSetJson(
    val side: com.pokemon.battle.model.Side,
    val condition: SideCondition,
    val turnsRemaining: Int,
) : GameEventJson {
    override fun toDomain() = SideConditionSet(side, condition, turnsRemaining)
}

@Serializable
data class SideConditionTickJson(
    val side: com.pokemon.battle.model.Side,
    val condition: SideCondition,
    val turnsRemaining: Int,
) : GameEventJson {
    override fun toDomain() = SideConditionTick(side, condition, turnsRemaining)
}

@Serializable
data class SideConditionExpiredJson(
    val side: com.pokemon.battle.model.Side,
    val condition: SideCondition,
) : GameEventJson {
    override fun toDomain() = SideConditionExpired(side, condition)
}

// --- Gimmick ---

@Serializable
data class GimmickUsedJson(val kind: GimmickKind, val slot: Slot) : GameEventJson {
    override fun toDomain() = GimmickUsed(kind, slot)
}

// --- Hazards ---

@Serializable
data class HazardSetJson(
    val side: com.pokemon.battle.model.Side,
    val hazard: SideHazard,
    val layers: Int,
) : GameEventJson {
    override fun toDomain() = HazardSet(side, hazard, layers)
}

@Serializable
data class HazardRemovedJson(
    val side: com.pokemon.battle.model.Side,
    val hazard: SideHazard,
) : GameEventJson {
    override fun toDomain() = HazardRemoved(side, hazard)
}

@Serializable
data class HazardDamageJson(val target: Slot, val amount: Int, val hazard: SideHazard) : GameEventJson {
    override fun toDomain() = HazardDamage(target, amount, hazard)
}

// --- Control events (pipeline pause/resume) ---

@Serializable
data class TurnPausedForInputJson(val request: InputRequestJson, val atPhaseIndex: Int) : ControlEventJson {
    override fun toDomain() = TurnPausedForInput(request.toDomain(), atPhaseIndex)
}

@Serializable
data class TurnInputResolvedJson(val response: InputResponseJson) : ControlEventJson {
    override fun toDomain() = TurnInputResolved(response.toDomain())
}

// --- Input request / response DTOs ---

@Serializable
sealed interface InputRequestJson {
    fun toDomain(): InputRequest
}

@Serializable
data class SwitchTargetRequestJson(
    val userSlot: Slot,
    val reason: SwitchReason,
    val eligibleBenchIndices: List<Int>,
) : InputRequestJson {
    override fun toDomain() = SwitchTargetRequest(userSlot, reason, eligibleBenchIndices)
}

@Serializable
sealed interface InputResponseJson {
    fun toDomain(): InputResponse
}

@Serializable
data class SwitchTargetResponseJson(val benchIndex: Int) : InputResponseJson {
    override fun toDomain() = SwitchTargetResponse(benchIndex)
}

// --- Domain → DTO conversion ---

@Suppress("CyclomaticComplexMethod") // Exhaustive when over all event variants
fun BattleEvent.toJson(): BattleEventJson =
    when (this) {
        is MoveOrderDecided -> MoveOrderDecidedJson(order, leadReason)
        is MoveAttempted -> MoveAttemptedJson(attacker, move)
        is MoveFailed -> MoveFailedJson(attacker, reason)
        is DamageDealt -> DamageDealtJson(target, amount, effectiveness, critical)
        is PokemonFainted -> PokemonFaintedJson(slot)
        is ProtectBlocked -> ProtectBlockedJson(slot)
        is StatChanged -> StatChangedJson(target, stat, stages)
        is TypeChanged -> TypeChangedJson(target, newTypes)
        is VolatileAdded -> VolatileAddedJson(target, volatile)
        is VolatileRemoved -> VolatileRemovedJson(target, volatile)
        is StatusApplied -> StatusAppliedJson(target, status)
        is StatusCleared -> StatusClearedJson(target, status)
        is StatusDamage -> StatusDamageJson(target, amount, source)
        is WeatherDamage -> WeatherDamageJson(target, amount, weather)
        is WeatherTick -> WeatherTickJson(weather, turnsRemaining)
        is WeatherSet -> WeatherSetJson(weather, turnsRemaining)
        is TrickRoomSet -> TrickRoomSetJson(turnsRemaining)
        is ItemHealing -> ItemHealingJson(target, amount, item)
        is ItemConsumed -> ItemConsumedJson(target, item)
        is ItemDamage -> ItemDamageJson(target, amount, item)
        is SwitchOut -> SwitchOutJson(slot)
        is SwitchIn -> SwitchInJson(slot, benchIndex)
        is AbilityTriggered -> AbilityTriggeredJson(slot, ability)
        is AbilityBlocked -> AbilityBlockedJson(slot, ability)
        is SideConditionSet -> SideConditionSetJson(side, condition, turnsRemaining)
        is SideConditionTick -> SideConditionTickJson(side, condition, turnsRemaining)
        is SideConditionExpired -> SideConditionExpiredJson(side, condition)
        is GimmickUsed -> GimmickUsedJson(kind, slot)
        is HazardSet -> HazardSetJson(side, hazard, layers)
        is HazardRemoved -> HazardRemovedJson(side, hazard)
        is HazardDamage -> HazardDamageJson(target, amount, hazard)
        is TurnPausedForInput -> TurnPausedForInputJson(request.toJson(), atPhaseIndex)
        is TurnInputResolved -> TurnInputResolvedJson(response.toJson())
    }

fun InputRequest.toJson(): InputRequestJson =
    when (this) {
        is SwitchTargetRequest -> SwitchTargetRequestJson(userSlot, reason, eligibleBenchIndices)
    }

fun InputResponse.toJson(): InputResponseJson =
    when (this) {
        is SwitchTargetResponse -> SwitchTargetResponseJson(benchIndex)
    }
