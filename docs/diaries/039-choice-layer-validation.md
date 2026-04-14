# Diary 039: Choice-Layer Validation (Planning)

**Date:** 2026-04-14
**Status:** Complete

## The gap

Several mechanics emit state that *should* restrict future choices but we don't enforce
any of it at the engine level:

| Mechanic | State emitted | Currently enforced? |
|----------|--------------|---------------------|
| Choice Band/Specs/Scarf | `Volatile.ChoiceLocked(move)` | ❌ — AI/UI is trusted to respect |
| Encore (future) | `Volatile.Encore(move, turns)` | ❌ |
| Disable (future) | `Volatile.Disable(move, turns)` | ❌ |
| Taunt (future) | `Volatile.Taunt(turns)` (no status moves) | ❌ |
| Torment (future) | `Volatile.Torment` (no repeating last move) | ❌ |
| Heal Block (future) | `Volatile.HealBlock(turns)` | ❌ |
| Gimmick budgets | `Ruleset.canUseGimmick` | ✅ (exists, no caller yet) |
| Banlists / format legality | — | ❌ — no hook |
| Trap abilities (Shadow Tag, Arena Trap) | — | ❌ — can't block switches |

Today a buggy AI or a bad CLI input could submit `TurnChoice.UseMove(otherMove)` for a
Choice-locked Pokemon and the engine would dutifully execute it. That's a real fidelity
gap.

## Two places validation can live

### Option A: Submission-time validation
Before the pipeline runs, a `TurnChoiceValidator` rejects invalid choices. Caller must
fix and re-submit.

**Pros:** Choices that reach the pipeline are already valid — simpler engine code. AI/UI
gets a clear "this is illegal" signal.

**Cons:** Choice construction and validation become a round-trip. State can mutate
between validation and execution (rare in our synchronous model but conceptually
possible).

### Option B: Execution-time failure
Engine accepts any choice; if it turns out to be illegal, emit `MoveFailed(reason)` and
skip. Same pattern as paralysis/sleep/freeze today.

**Pros:** Defensive — works even if the caller has a bug. No round-trip. Consistent
with how status-induced move failures already work.

**Cons:** Illegal-but-plausible actions consume a "turn" — the attacker "wasted" their
pick even though the choice was never legal. Arguable whether that's right or wrong.

### The right answer: both

Pokemon games do both. The UI grays out illegal moves (submission-time); the engine
still has "But it failed!" for state that changed between selection and resolution.

We should ship **Option B first** (defensive engine) because it makes the engine
correct without requiring a choice-layer rewrite. Then add **Option A** later as a
helper that AI/UI can consult — `BattleState.validMovesFor(slot): Set<Move>?` — for
convenience, not safety.

## Design (Option B)

### New `Ruleset` hook

```kotlin
interface Ruleset {
    // existing:
    fun canUseGimmick(...): Boolean = false

    // new:
    fun canUseMove(
        state: BattleState,
        userSlot: Slot,
        move: Move,
    ): MoveLegality = MoveLegality.Allowed
}

sealed interface MoveLegality {
    object Allowed : MoveLegality
    data class Forbidden(val reason: FailReason) : MoveLegality
}
```

Default ruleset returns `Allowed`. Concrete rulesets add their own restrictions.

### Volatile checks as a default implementation

Most restrictions are volatile-based and gen-stable (Choice-lock, Disable, Encore,
Taunt, Torment). These belong in a shared helper that the default `Ruleset`
implementation consults:

```kotlin
// Shared helper used by every concrete ruleset
fun volatileBasedMoveLegality(
    state: BattleState,
    userSlot: Slot,
    move: Move,
): MoveLegality {
    val user = state.pokemonFor(userSlot)
    val lock = user.volatiles.filterIsInstance<Volatile.ChoiceLocked>().firstOrNull()
    if (lock != null && lock.move != move) {
        return MoveLegality.Forbidden(FailReason.CHOICE_LOCKED)
    }
    // Future: Disable, Encore, Taunt, Torment, HealBlock
    return MoveLegality.Allowed
}
```

Format-specific rulesets (NationalDex, Gen9VgcTeraRuleset) would compose:

```kotlin
object PokemonChampionsRuleset : Ruleset {
    override fun canUseMove(state, userSlot, move) =
        volatileBasedMoveLegality(state, userSlot, move)
        // Future: also apply banlist check
}
```

### MoveExecutionPhase check

Early in `executeMove`, after the existing `requiresJustSwitchedIn` check:

```kotlin
val legality = state.ruleset.canUseMove(state, attackerSlot, move)
if (legality is MoveLegality.Forbidden) {
    events.add(MoveFailed(attackerSlot, legality.reason))
    return events
}
```

### New FailReason values
- `CHOICE_LOCKED` — "X is locked into <move>"
- Future: `MOVE_DISABLED`, `TAUNTED`, `TORMENTED`, `HEAL_BLOCKED`, `ENCORED`

### BattleState convenience helper (optional, for callers)

```kotlin
// On BattleState — returns null if no restrictions, else the set of legal moves.
// AI/UI calls this before presenting the move list.
fun validMovesFor(slot: Slot, candidates: List<Move>): List<Move> =
    candidates.filter { ruleset.canUseMove(this, slot, it) is MoveLegality.Allowed }
```

Not required for engine correctness; exists to spare the choice layer from reproducing
the check.

## Plan

### Step 1: `MoveLegality` sealed + `canUseMove` on Ruleset
- [x] `MoveLegality` sealed type
- [x] Default `Ruleset.canUseMove(...)` returns `Allowed`
- [x] Shared `volatileBasedMoveLegality` helper
- [x] `PokemonChampionsRuleset`, `NationalDexRuleset`, `Gen9VgcTeraRuleset` all override
      `canUseMove` to delegate to the shared helper

### Step 2: `FailReason.CHOICE_LOCKED`
- [x] Enum entry
- [x] TextRenderer branch ("X is locked into <move>!")

### Step 3: Enforce in MoveExecutionPhase
- [x] Check `state.ruleset.canUseMove(...)` near the start of `executeMove`
- [x] Emit `MoveFailed` with the reason; return without executing

### Step 4: BattleState.validMovesFor helper
- [x] Convenience for AI/UI — not consumed by the engine

### Step 5: Tests
- [x] Choice-locked user submitting a different move fails with CHOICE_LOCKED
- [x] Choice-locked user submitting the locked move succeeds normally
- [x] Pokemon without ChoiceLocked can use any move under the same ruleset
- [x] `validMovesFor` returns only the legal subset under a lock

## Implementation notes (2026-04-14)

- Placed the legality check in `executeMove` after `requiresJustSwitchedIn`, as the
  design section specified. Status-induced failures (sleep/paralyze/freeze) still run
  *before* `executeMove`; a sleeping choice-locked Pokemon will report "is fast asleep!"
  rather than "locked into X!" on the turn it's asleep. Consistent with mainline
  games — sleep gates the whole attempt.
- `TextRenderer.renderMoveFailed` reads the `ChoiceLocked` volatile off
  `stateBefore.pokemonFor(event.attacker)` to name the locked move. Falls back to
  "But it failed!" if the volatile is absent (defensive — shouldn't happen in practice).
- Tests use `BattleState.singles(...).copy(ruleset = PokemonChampionsRuleset)` to opt
  into enforcement. `NoGimmicksRuleset` (the default) also allows everything by default,
  so production setups that haven't picked a ruleset remain unchanged.

## Deferred

- **Disable / Encore / Taunt / Torment / Heal Block** — same shape, different volatiles.
  Add them when their setter moves arrive.
- **Banlists** — `Ruleset` can gate moves/species/items at team-construction time;
  that's a different layer entirely. Not engine concern.
- **Trap abilities** — `Ruleset.canSwitch(state, slot)` is the matching hook; add when
  Shadow Tag / Arena Trap / Ingrain are first needed.
- **Submission-time validation** — after execution-time is solid, add a
  `validateChoices(choices, state): List<ValidationError>` helper for callers that want
  to pre-check.

## Why not now

Two reasons:
1. **Entry hazards next** — higher-impact VGC mechanic, tests our SideCondition
   generalization. Validation plumbing can wait for a moment when enforcement actually
   bites (I add AI that picks a wrong move, or the current AI is observed violating a
   lock).
2. **The Ruleset interface should grow with demand** — I already shipped `canUseGimmick`
   without a consumer (GimmickUsed isn't yet emitted by any move). Adding `canUseMove`
   before there's a caller risks over-designing the return type.

When we build Mega Evolution (first real gimmick) or enforce-time arrives, this diary
tells future-us exactly what to build.

## Related

- **Diary 022** — Choice items emit `ChoiceLocked`; this enforces it
- **Diary 036** — Ruleset stub; this is the second method
- **Diary 030** — meta-lesson 1 ("Pluggable Ruleset that gates choice legality")
