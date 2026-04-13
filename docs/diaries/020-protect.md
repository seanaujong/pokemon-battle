# Diary 020: Protect

**Date:** 2026-04-13
**Status:** Complete

## Goal

Implement Protect — a status move that blocks all attacks targeting the user for
one turn. This is the first per-target blocking mechanic, exercising
`Volatile.Protect` (which we defined early on but never used) and adding a
pre-damage check to `MoveExecutionPhase.resolveDamage`.

## How Protect works

1. User selects Protect as their move (STATUS category, SELF target)
2. Protect always goes first (+4 priority in the games)
3. A `Volatile.Protect` is set on the user
4. Any move targeting the protected Pokemon fails with a message
5. Protect is cleared at the end of the turn
6. Using Protect consecutively halves the success rate (50%, 25%, etc.)

## Design

### Move definition

```kotlin
val PROTECT = register(Move("Protect", Type.NORMAL, MoveCategory.STATUS, 0,
    priority = 4, target = MoveTarget.SELF,
    effects = listOf(MoveEffect.SetVolatile(Volatile.Protect))))
```

This needs a new `MoveEffect`: `SetVolatile(volatile: Volatile)`.

### Per-target blocking in resolveDamage

Before the ability immunity check, add a Protect check:

```
for target in targets:
    if target has Volatile.Protect → emit MoveFailed/ProtectBlocked, skip
    check ability immunity → skip if immune
    calculate damage
    check faint
```

Need a new event: `ProtectBlocked(slot)` — informational, renders as
"Venusaur protected itself!"

### Clearing Protect at end of turn

`EndOfTurnPhase` (or a new phase) removes `Volatile.Protect` from all Pokemon
at the end of each turn. This prevents Protect from lasting multiple turns.

### Consecutive Protect penalty

Track consecutive Protect uses somehow. Options:
- A counter volatile: `Volatile.ProtectCounter(consecutiveUses: Int)`
- Check in `MoveExecutionPhase` before setting the volatile

Defer the consecutive penalty for the first implementation — just get basic
Protect working, add the penalty later.

## Plan

### Step 1: MoveEffect.SetVolatile
- [x] Add `SetVolatile(volatile: Volatile)` to `MoveEffect` sealed interface
- [x] `resolveEffect` in `MoveExecutionPhase` handles it — emits `VolatileAdded`
- [x] Compile check

### Step 2: Protect move definition
- [x] Add to `MoveDex` with priority +4
- [x] Compile check

### Step 3: ProtectBlocked event and per-target check
- [x] `ProtectBlocked(slot)` informational event
- [x] `TextRenderer` renders "X protected itself!"
- [x] `resolveDamage` checks `Volatile.Protect` before damage calc (before ability immunity)
- [x] Compile check

### Step 4: Clear Protect at end of turn
- [x] `EndOfTurnPhase` removes `Volatile.Protect` from all slots after other effects

### Step 5: Tests
- [x] Protect blocks a single-target move
- [x] Protect gains priority (+4 goes before faster attacker)
- [x] Protect user can still apply self-target effects (Swords Dance)
- [x] Protect is cleared at end of turn — next turn the Pokemon is vulnerable
- [x] Protect volatile is added during the turn
- [x] Second turn without Protect is vulnerable

## Decisions along the way

- **Split `VolatileChanged` into `VolatileAdded` and `VolatileRemoved`.** The original nullable
  design (`old: Volatile?, new: Volatile?`) allowed illegal states (both null) and conflated
  add/remove/transition. Splitting into two events makes illegal states unrepresentable and
  reads more literally. Sleep counter decrement is now two events (remove old, add new), which
  is honest — it *is* two state changes.
- **Protect check happens before ability immunity.** A Protect user should not reveal their
  defensive ability (e.g. Levitate) when they successfully Protect.
- **`SetVolatile` is defined on `MoveEffect`, not as a primitive in the phase.** Keeps move
  definitions declarative and data-driven.

## Implemented in same diary

- **Consecutive-use penalty.** `Volatile.ProtectCounter(consecutive: Int)` tracks consecutive
  uses (incremented even on failure). Success chance is `100 shr consecutive`: 100%, 50%, 25%,
  12%, 6%, 3%, 1%. Cleared automatically by SwitchPhase or by the user choosing any non-Protect
  move. **Note:** real Gen V+ uses 1/3^N (33%, 11%, 3.7%) which decays harder; we chose
  halving for simplicity and clarity.
- **Status-move blocking.** Refactored from per-target check inside `resolveDamage` to a single
  `applyProtectGate` upstream of both damage and effects. Status moves like Growl now correctly
  bounce off Protect.

## Code design

`MoveEffect.SetVolatile(Volatile.Protect)` is the data marker that makes a move a "protection
move". `executeMove` detects it via `isProtectionMove(move)` and branches into
`resolveProtectionMove` which owns the diminishing-success logic. This keeps move definitions
declarative (just data) while letting one phase own the special-case mechanic.

If we add Detect / Spiky Shield they'd register the same way and reuse `Volatile.ProtectCounter`.

## Validation

| Step | Validation |
|------|-----------|
| 1-4 | `./gradlew compileKotlin` — passed |
| 5 | `./gradlew test` — 132 tests pass |
