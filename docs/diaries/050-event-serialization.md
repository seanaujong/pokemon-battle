# 050 — Event serialization (JSON round-trip)

## Goal

Unlock `BattleEvent` as a first-class data asset by making the sealed hierarchy
`kotlinx-serialization`-ready. Events already drive rendering (diary 038) and
have been flagged as the universal audit stream (diary 042). Serialization is
the cheap precondition for analytics, replay, structured logs, and JSON wire
formats — we don't build any of those consumers yet, just the unlock.

## What was annotated

Gradle:
- Added `kotlin("plugin.serialization") version "2.2.10"` to `plugins {}`.
- Added `implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")`.

Engine — `@Serializable` on the sealed interface and every concrete subclass:
- `BattleEvent.kt`: `BattleEvent` (sealed interface), `MoveOrderDecided`,
  `MoveAttempted`, `MoveFailed`, `DamageDealt`, `PokemonFainted`,
  `ProtectBlocked`.
- `AbilityEvents.kt`: `AbilityTriggered`, `AbilityBlocked`.
- `ItemEvents.kt`: `ItemHealing`, `ItemConsumed`, `ItemDamage`.
- `StatEvents.kt`: `StatChanged`, `TypeChanged`, `VolatileAdded`,
  `VolatileRemoved`.
- `StatusEvents.kt`: `StatusApplied`, `StatusDamage`, `StatusCleared`.
- `SwitchEvents.kt`: `SwitchOut`, `SwitchIn`.
- `WeatherEvents.kt`: `WeatherDamage`, `WeatherTick`, `WeatherSet`,
  `TrickRoomSet`.
- `SideConditionEvents.kt`: `SideConditionSet`, `SideConditionTick`,
  `SideConditionExpired`.
- `HazardEvents.kt`: `HazardSet`, `HazardRemoved`, `HazardDamage`.
- `GimmickUsed.kt`: `GimmickUsed`.

Model — data classes and sealed hierarchies referenced by events:
- `Slot` (data class) — used almost everywhere.
- `Move` (data class) — referenced by `MoveAttempted` and
  `Volatile.ChoiceLocked`.
- `MoveEffect` (sealed interface + all variants) — reachable transitively
  through `Move.effects`.
- `Volatile` (sealed interface + all variants) — referenced by
  `VolatileAdded`/`VolatileRemoved`.

Enums (`Side`, `Item`, `Ability`, `StatusCondition`, `FailReason`, `Type`,
`Effectiveness`, `SideCondition`, `SideHazard`, `GimmickKind`, `StatType`,
`OrderReason`, `Weather`, `MoveCategory`, `MoveTarget`) don't need
`@Serializable` — `kotlinx-serialization` supports enums out of the box.

## Tricky cases

- **`Move.hitCount: IntRange?`**: `kotlinx-serialization` has no built-in
  `IntRange` serializer. Marked `@Transient` and defaulted to `null`. This is
  acceptable because multi-hit count is a *definition-time* attribute; the
  *observed* hit count manifests as the number of `DamageDealt` events in the
  stream, which is what an auditor/analytics/replay actually cares about. We
  accept losing the field on round-trip for now; a custom `IntRangeSerializer`
  is a trivial follow-up if a downstream consumer needs it.
- **`ChoiceLocked(Move)` → Move → MoveEffect**: making `Volatile.ChoiceLocked`
  serializable forces the whole `Move` → `MoveEffect` chain to be serializable.
  Fine — they're all plain data.

## Usage

```kotlin
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

val json = Json { classDiscriminator = "type" }
val events: List<BattleEvent> = pipeline.resolve(state, choices).events
val encoded: String = json.encodeToString(ListSerializer(BattleEvent.serializer()), events)
val decoded: List<BattleEvent> = json.decodeFromString(ListSerializer(BattleEvent.serializer()), encoded)
```

The sealed-interface polymorphism inserts a `"type"` discriminator
(fully-qualified class name by default) on each event so the decoder can pick
the right variant.

## Validation

- `./gradlew compileKotlin` — clean.
- `./gradlew test ktlintCheck detekt` — clean (new test passes;
  +1 test: `EventSerializationTest`).
- `EventSerializationTest` runs a full pipeline turn, encodes the event list
  to JSON, decodes it back, and asserts `events == decoded`. The equality
  check is strict — any dropped / mistyped field would fail it.

## What this unlocks

None of these are implemented now — serialization is just the prerequisite:

- **Analytics.** Stream events to an external store; run aggregations like
  "crit rate by move" or "% turns with weather active" without touching engine
  code.
- **Replay.** Persist `List<BattleEvent>` for a match, then re-apply them to
  the initial state to reconstruct any mid-turn snapshot.
- **Structured logging.** Emit JSON lines for observability; grep / jq an
  entire season of matches without bespoke parsers.
- **Wire format.** Future client/server split or cross-language analytics
  (Python notebooks, etc.) can consume the same events the engine emits.

## Looking ahead

- If / when a consumer needs `Move.hitCount` over the wire, write a small
  `IntRangeSerializer` (encode as `{ "first": Int, "last": Int }`) and drop
  the `@Transient`.
- Wire-format stability is explicitly *not* a goal yet. Class discriminators
  default to fully-qualified names; if we ship events externally, pick
  `@SerialName` aliases at that time.
- `BattleState` is deliberately *not* serializable. Events are the audit
  stream; state is the current fold. Making state serializable would be a
  separate decision (snapshotting, debugging tools) with its own trade-offs.
