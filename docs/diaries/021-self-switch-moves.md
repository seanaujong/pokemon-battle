# Diary 021: Self-Switch Moves (U-turn, Volt Switch)

**Date:** 2026-04-13
**Status:** Not started

## Goal

Implement moves that deal damage then switch the attacker out. This is the first
`MoveEffect` that triggers a switch during move execution, connecting the move
effects system to the switching infrastructure.

## How U-turn / Volt Switch work

1. Attacker uses the move — damage is dealt normally
2. After damage, the attacker switches out and a bench Pokemon switches in
3. If the move misses, or the target is immune (type or ability), no switch
4. If the attacker faints from recoil (not applicable here), no switch
5. The switch triggers switch-in abilities (Intimidate, Drizzle)

## Design

### New MoveEffect

```kotlin
MoveEffect.SelfSwitch : MoveEffect
```

A flag effect — no parameters. After damage is resolved, the phase checks
for `SelfSwitch` in the move's effects and triggers the switch.

### The switch decision

After dealing damage, if the move has `SelfSwitch`:
1. Check if the attacker is still alive (not fainted)
2. Check if bench has available Pokemon
3. If yes: need to know *which* bench Pokemon to send in

This is a choice — the player or AI picks the replacement. But we're mid-turn
inside `MoveExecutionPhase`, and choices were submitted at the start. Options:

A. **Pre-declare the switch target in `TurnChoice.UseMove`** — add an optional
   `switchTo: Int?` field for the bench index if the move has SelfSwitch
B. **Use `FaintReplacementProvider`** — same interface as faint replacement,
   call it during move execution
C. **Always switch to first bench Pokemon** — simplest, defer choice to later

Option B is the cleanest — the game loop already has a replacement provider,
and self-switch is conceptually similar (pick a bench Pokemon for a slot).
But `FaintReplacementProvider` is on `BattleLoop`, not accessible from phases.

Option A keeps everything in the choice system. The player knows they're using
U-turn and picks both the move and the switch target upfront. This matches how
the games work — you choose U-turn knowing you'll switch.

**Decision:** Option A. Add `switchTo: Int?` to `TurnChoice.UseMove`. Null for
normal moves. Set to bench index for self-switch moves.

### Events emitted

After damage:
1. `SwitchOut(attackerSlot)` — with volatile/stat clearing from `SwitchPhase`
2. `SwitchIn(attackerSlot, benchIndex)` — bench Pokemon enters
3. Switch-in ability triggers via `resolveSwitchInAbility`

These are the same events as voluntary switching — the renderer already handles them.

### Move definitions

```kotlin
val U_TURN = register(Move("U-turn", Type.BUG, MoveCategory.PHYSICAL, 70,
    effects = listOf(MoveEffect.SelfSwitch)))

val VOLT_SWITCH = register(Move("Volt Switch", Type.ELECTRIC, MoveCategory.SPECIAL, 70,
    effects = listOf(MoveEffect.SelfSwitch)))
```

### Clearing logic

The self-switch needs to clear volatiles and stat stages, same as voluntary
switching. We can reuse `SwitchPhase.clearOnSwitchOut` — but it's currently
a private method on `SwitchPhase`. Options:
- Extract to a shared function (like `resolveSwitchInAbility`)
- Duplicate in `MoveExecutionPhase`
- Make `SwitchPhase.clearOnSwitchOut` internal/public

**Decision:** Extract to a shared function `resolveSwitchOutClearing` in
`engine/`, mirroring `resolveSwitchInAbility`.

## Plan

### Step 1: MoveEffect.SelfSwitch
- [ ] Add `data object SelfSwitch : MoveEffect`
- [ ] Compile check — `resolveEffect` `when` branch needs updating

### Step 2: TurnChoice.UseMove.switchTo
- [ ] Add `switchTo: Int? = null` to `UseMove`
- [ ] Compile check — existing code unaffected (default null)

### Step 3: Extract switch-out clearing
- [ ] `resolveSwitchOutClearing(state, slot): List<BattleEvent>` in engine
- [ ] `SwitchPhase` delegates to it
- [ ] Compile and test — existing switching tests still pass

### Step 4: Handle SelfSwitch in MoveExecutionPhase
- [ ] After damage + effects in `executeMove`, check for `SelfSwitch`
- [ ] If present and attacker alive and bench available and `switchTo` set:
      emit clearing events, SwitchOut, SwitchIn, ability triggers
- [ ] Use `resolveSwitchOutClearing` and `resolveSwitchInAbility`

### Step 5: Move definitions
- [ ] Add U-turn and Volt Switch to `MoveDex`

### Step 6: Tests
- [ ] U-turn deals damage then switches attacker out
- [ ] Replacement Pokemon enters with switch-in ability trigger
- [ ] Volatiles and stat stages cleared on switch
- [ ] If move is blocked (type immunity), no switch occurs
- [ ] If bench is empty, no switch occurs (damage still happens)
- [ ] If attacker faints from... N/A, U-turn has no recoil. Skip.

## Validation

| Step | Validation |
|------|-----------|
| 1-5 | `./gradlew compileKotlin` |
| 6 | `./gradlew test` — all tests pass |
