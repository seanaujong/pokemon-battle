# Diary 002: Extended Example — Priority, Status, Weather, Items

**Date:** 2026-04-12
**Status:** Complete

## Goal

Implement the Infernape vs Swampert scenario from `docs/example-extended.md`. This exercises priority ordering, burn's damage penalty, and a full EndOfTurnPhase with weather damage, status damage, and item healing.

## Questions (answered)

1. **Sandstorm immunity:** Implement both type-based (Rock/Ground/Steel) and ability-based immunity as a test of extensibility. Type immunity is the baseline; ability immunity layers on top.
2. **EndOfTurnPhase sub-ordering:** Hardcode the real game order (weather → status → items → weather tick). Each sub-concern is a private function for readability. Configuration is unnecessary.
3. **Test approach:** Full end-to-end example replay, matching the event sequence from the doc.

## Plan

### Step 1: Weather damage in EndOfTurnPhase (done)
- [x] Implement sandstorm damage (1/16 max HP) for non-Rock/Ground/Steel types
- [x] Implement ability-based sandstorm immunity (Sand Veil, Sand Rush, Sand Force)
- [x] Also added hail damage with Ice type immunity and Snow Cloak/Ice Body ability immunity
- [x] Emit `WeatherDamage` events
- [x] Fainted Pokemon skipped

### Step 2: Status damage in EndOfTurnPhase (done)
- [x] Implement burn damage (1/16 max HP per turn)
- [x] Implement poison damage (1/8 max HP per turn)
- [x] Emit `StatusDamage` events
- [x] Fainted Pokemon skipped

### Step 3: Item healing in EndOfTurnPhase (done)
- [x] Implement Leftovers (restore 1/16 max HP per turn, only if below max)
- [x] New event type: `ItemHealing` with `apply()` that increases HP (capped at max)
- [x] Emit `ItemHealing` events

### Step 4: Weather tick (done)
- [x] New event type: `WeatherTick` with `apply()` that decrements turns, clears weather at 0
- [x] Emitted at end of EndOfTurnPhase

### Step 5: End-to-end test — Infernape vs Swampert (done)
- [x] Full 9-event sequence validated
- [x] Priority ordering (Mach Punch +1), burn halving physical damage, sandstorm type immunity, burn damage, Leftovers healing, weather tick
- [x] Separate tests for: sandstorm Ground-type immunity, ability-based immunity (Sand Veil), burn damage ratio

## Validation

| Step | Validation | Result |
|------|-----------|--------|
| 1-4 | `./gradlew compileKotlin` succeeds | PASS |
| 5 | `./gradlew test` — 8 tests pass (4 from diary 001 + 4 new) | PASS |

## Decisions made

- **EndOfTurnPhase uses `Player.entries`** to iterate, per format audit guidance (avoids hardcoding P1/P2).
- **`isWeatherImmune` is a standalone function** in `Ability.kt`, dispatching on ability + weather type. Simple enough for now; can extract to an ability effect system later if it grows.
- **`Ability` enum expanded** with weather-immunity abilities (Sand Veil, Sand Rush, Sand Force, Snow Cloak, Ice Body).

## Bug found in docs

**`docs/example-extended.md` had Ground vs Fire as neutral (1x).** It's actually super-effective (2x). Fixed the doc — Earthquake now deals ~110 damage instead of ~56, leaving Infernape at 33 HP instead of 87. The event sequence structure is the same; only the damage number and effectiveness label changed.
