# Diary 020: Protect

**Date:** 2026-04-13
**Status:** Not started

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
- [ ] Add `SetVolatile(volatile: Volatile)` to `MoveEffect` sealed interface
- [ ] `resolveEffect` in `MoveExecutionPhase` handles it — emits `VolatileChanged`
- [ ] Compile check

### Step 2: Protect move definition
- [ ] Add to `MoveDex` with priority +4
- [ ] Compile check

### Step 3: ProtectBlocked event and per-target check
- [ ] `ProtectBlocked(slot)` informational event
- [ ] `TextRenderer` renders "X protected itself!"
- [ ] `resolveDamage` checks `Volatile.Protect` before damage calc
- [ ] Compile check

### Step 4: Clear Protect at end of turn
- [ ] `EndOfTurnPhase` removes `Volatile.Protect` from all slots after other effects
- [ ] Or: a separate volatile clearing step

### Step 5: Tests
- [ ] Protect blocks a single-target move
- [ ] Protect blocks a spread move for the protected target only
- [ ] Protect doesn't block self-targeting moves (Swords Dance)
- [ ] Protect is cleared at end of turn — next turn the Pokemon is vulnerable
- [ ] Protect's +4 priority means it goes before most moves

## Validation

| Step | Validation |
|------|-----------|
| 1-4 | `./gradlew compileKotlin` |
| 5 | `./gradlew test` — all tests pass |
