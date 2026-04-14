# Diary 075: Natural Cure — adding an `onSwitchOut` hook

**Status:** shipped.

## Goal

Add Natural Cure to the engine. The ability is: when the holder switches out
(voluntarily, or via U-turn / Volt Switch), its non-volatile status condition
is cleared. This exercises `docs/skills/add-ability.md` Extension 1a — "the
hook you need doesn't exist" — because `AbilityEffect` had no switch-out seam.

## Hook signature — `onSwitchOut(state, slot)`

Settled on the minimal signature that mirrors `onSwitchIn`:

```kotlin
fun onSwitchOut(state: BattleState, slot: Slot): List<GameEvent> = emptyList()
```

Natural Cure only needs to read `state.pokemonFor(slot).status`. Regenerator
(likely next user of this hook) needs `currentHp` and `maxHp` — also readable
from `state` + `slot`. No reason to pass the registry (the hook is the dispatch
point, and any downstream lookups could be re-derived from state). If a future
ability needs opposing-slot info for switch-out reasons (currently I can't
name one), we widen then.

## Ordering decision

There are three events in the switch-out region of `SwitchPhase` /
`doSelfSwitch`:

1. `onSwitchOut` (new)
2. `resolveSwitchOutClearing` — clears volatiles + stat stages (gen-specific rule)
3. `SwitchOut` event — moves the Pokemon to the bench

I placed `onSwitchOut` first: **before** both `resolveSwitchOutClearing` and
`SwitchOut`. Reasoning:

- Natural Cure reads `status`, which isn't touched by `resolveSwitchOutClearing`
  (that clears volatiles/stat stages only). So the order doesn't affect the
  *correctness* of Natural Cure specifically.
- But conceptually: `resolveSwitchOutClearing` is the *generic gen rule* for
  leaving the field. An ability effect reading the Pokemon's state ought to
  see it as it was when the turn started, not as a partially-cleared husk.
  Running abilities first preserves that invariant and keeps "the ability
  sees the outgoing Pokemon's full state" true by construction.
- Symmetric with `onSwitchIn`, which currently fires *after* the `SwitchIn`
  event (the new Pokemon is on the field when its ability fires). The
  analogous symmetry for switch-out is "the old Pokemon is still on the
  field when its ability fires" — i.e. before `SwitchOut`. I did that.

This was obvious *after* writing it out; it wasn't obvious to me at first.
The friction isn't "what should the order be" so much as "what's the *reason*
for the order" — and the reason only crystallizes when you name a specific
future ability (Regenerator, which reads HP — also unaffected by clearing,
but the symmetry argument still lands).

## Was Extension 1a accurate?

Mostly yes, with one friction point.

**What held up:** the skill doc's instruction — "add a defaulted method to
`AbilityEffect` and wire it in the *one* place that triggers it in the
pipeline (a phase, a resolver, or `BattleLoop`)" — is correct in spirit. I
found the call site quickly. The "no `when (ability)` dispatch at the call
site — the registry is the dispatch" reminder was exactly the right nudge.

**Where it misled me briefly:** "the *one* place" is wrong for switch-out.
There are **two** natural call sites: `SwitchPhase` (voluntary switch) and
`MoveExecutionPhase.doSelfSwitch` (U-turn / Volt Switch). The shared helper
`resolveSwitchOutClearing` already exists — it's the *generic clearing
resolver*, not a switch-out ability resolver. I could have either:

1. Added a new `resolveSwitchOutAbility` resolver (mirror of
   `resolveSwitchInAbility`) and called it from both sites, or
2. Inlined the four-line lookup at both sites.

I chose (2) because it's small enough to read at both call sites. But the
friction is real: the skill doc implies one call site, and the existing
"switch" flow has two. A future contributor adding e.g. Regenerator would
have to know to wire both sites; I almost missed `doSelfSwitch`.

**Proposed doc update** (see below) — make the two-site reality explicit.

## Proposed updates to `docs/skills/add-ability.md`

Not applied in this commit — flagging for the main agent to decide. In order
of confidence:

1. **(High confidence)** Update the hook inventory in the Preconditions
   section to list `onSwitchOut`. Today it says "as of diary 072" and lists
   10 hooks; add `onSwitchOut` with a one-line "fired when the holder
   voluntarily leaves the field (not on faint)" gloss. This is mechanical
   housekeeping.

2. **(High confidence)** Revise Extension 1a. The line "wire it in the
   *one* place that triggers it in the pipeline" is not always true. Rephrase
   to: "wire it at every call site that triggers the mechanic. For
   switch-out, that's both `SwitchPhase` (voluntary) and
   `MoveExecutionPhase.doSelfSwitch` (U-turn / Volt Switch) — faint
   replacement is a separate seam and intentionally doesn't trigger
   `onSwitchOut`." Name the seams so the next contributor doesn't have to
   re-derive them.

3. **(Medium confidence)** Add a short "seams inventory" table to the skill
   doc: *switch-in*, *switch-out* (voluntary only), *faint replacement*,
   *damage resolution*, *end-of-turn*. Natural Cure's "does not fire on
   faint" rule only makes sense once you can see those as distinct seams.
   This belongs in the "Related information" section as a link to the phase
   files, not an authoritative restatement (risk of drift).

4. **(Low confidence)** Mention the detekt `TooManyFunctions` threshold
   gotcha. I hit it on hook #12. Inline `@Suppress` with rationale is the
   right fix per Tooling Principle 3, but a contributor hitting this for
   the first time has to reason through whether to bump the config or
   suppress inline. One sentence in the skill doc saves the cycle.

## Architectural note — shared pre-switch-out seam?

The other parallel agent is adding `Item.WEAKNESS_POLICY`. That's a
damage-intercept item, not a switch item. But the broader question — should
there be a shared *pre-switch-out* seam that both `AbilityEffect` and
`ItemEffect` can hook into? — is worth flagging.

Today, the parallel to Natural Cure on the item side would be White Herb
(consumed on switch-out in some gens) or Eject Button (forces switch-in
mid-turn — different seam). Neither is on the current ItemEffect today.
If we add one, the question becomes: do items and abilities share a
`resolvePreSwitchOut(state, slot, registries)` resolver that fans out to
both registries, or do we wire them independently like `onSwitchIn` does?

My recommendation: defer the shared resolver until we have a concrete item
that needs it. The ability-side wiring is four lines per call site. Adding
a resolver now would be speculative, and the registries already handle the
"look up by ID" part — the resolver is just a fan-out loop. When the second
user appears, lifting a four-line inline into a six-line resolver is easy.

What we *should* do now: if `ItemEffect` eventually grows an `onSwitchOut`
hook, put it adjacent to the ability call in both sites, and factor then.
Diary 033's "put the check on the lookup, not every caller" lesson applies —
but we don't have a cross-cutting *suppression* concern here yet, just an
ordering one.

## Plan (what I did)

- [x] Add `Ability.NATURAL_CURE` to the enum.
- [x] Add defaulted `onSwitchOut(state, slot)` to `AbilityEffect` with
      documentation making the "not on faint" rule explicit.
- [x] Wire into `SwitchPhase` before `resolveSwitchOutClearing` and
      `SwitchOut`.
- [x] Wire into `MoveExecutionPhase.doSelfSwitch` at the symmetric location.
- [x] Create `NaturalCureEffect`: if holder has non-null status, emit
      `AbilityTriggered + StatusCleared`; otherwise empty.
- [x] Register in `GenVRegistries.abilities`.
- [x] Skipped custom render text — the fallback `"X's Natural cure!"` is
      passable.
- [x] Tests: voluntary switch clears status; U-turn self-switch clears status;
      no-op with no status; does NOT fire on faint.
- [x] Suppressed detekt `TooManyFunctions` on `AbilityEffect` with rationale
      (Tooling Principle 3).
- [x] `./gradlew test ktlintCheck detekt` — all green.

## Summary (one line)

Shipped Natural Cure and a new `AbilityEffect.onSwitchOut` hook wired at both
voluntary-switch sites (`SwitchPhase`, `MoveExecutionPhase.doSelfSwitch`),
with four tests covering fires/no-fires including the faint-is-not-a-switch
case.
