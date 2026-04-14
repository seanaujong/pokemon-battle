# Diary 047: Parallel Stress Test — What Actually Happened

**Date:** 2026-04-14
**Status:** Empirical findings from the 3-agent experiment

## The experiment

Diary 043 predicted parallelism chokepoints inside the engine module: shared registry
files, shared enum files, shared dispatch functions. To test whether those predictions
were right in practice, we ran three agents in parallel worktrees, each implementing
a feature deliberately chosen to touch at least one predicted chokepoint:

- **Agent A** — Hazard removal moves (Rapid Spin, Defog)
- **Agent B** — Heavy-Duty Boots item
- **Agent C** — Multi-hit moves (Rock Blast, Double Slap)

Each agent worked independently from `main`, committed to its own worktree branch, and
returned a summary of what it touched.

## Predicted vs actual

| Chokepoint | Prediction | Actual outcome |
|------------|-----------|----------------|
| `MoveDex` (A and C both add moves) | Likely conflict | **Clean** — git's 3-way merge reconciled different insertion points |
| `MoveEffect` sealed interface | Possible conflict if A and C both add variants | **Clean** — only A added variants; C used a field on `Move` instead |
| `MoveExecutionPhase.resolveDamage*` | A changed `resolveEffect` signature; C refactored `resolveDamagePerTarget` body | **Clean** — different methods in the same file |
| `Item` enum + `ItemRegistry` + `ItemEffect` | B only | **Clean** — no overlap |
| `HazardResolver` | B refactored the function structure | **Clean** — no overlap |

**All three branches merged cleanly into a throwaway `stress-test-merge` branch. 236
tests pass (221 prior + 15 new: 5 per feature). Zero manual conflict resolution.**

## Why the merge was clean

The prediction was that shared-file bottlenecks would cause conflicts. The reality is
more nuanced:

### 1. Insertion points differed

Agent A added `RAPID_SPIN` / `DEFOG` near the `PROTECT` move (line ~224 in `MoveDex`).
Agent C added `ROCK_BLAST` / `DOUBLE_SLAP` right after `MUD_SLAP` (line ~45). Different
insertion points → git's 3-way merge handled both additions without conflict.

**Lesson:** "everyone edits the same file" doesn't mean "everyone edits the same lines."
For append-mostly files (catalog registrations), git is good at parallel insertions at
different positions.

### 2. The agents self-organized around conflicts

Agent C chose to add `hitCount: IntRange?` as a field on the `Move` data class
rather than as a new `MoveEffect` variant. That decision avoided the sealed interface
file entirely — the very file Agent A was adding variants to. The agent made this
decision without coordination, based on what "fit best" for the mechanic.

This is a form of **emergent organization under the registry pattern**: when multiple
extension mechanisms exist (`MoveEffect` variants vs `Move` fields), contributors
naturally choose the one that matches their mechanic's shape, reducing collision risk.

### 3. Shared-helper refactors were local

Agent B refactored `resolveHazardsOnSwitchIn` (extracted `applyHazardsInOrder`).
Agent C refactored `resolveDamagePerTarget` (extracted `applyOneHit`). Agent A
threaded `attackerSlot` through `resolveEffects` / `resolveEffect`. Three different
shared helpers, three different files/methods, no overlap.

**Lesson:** "refactoring a shared function" sounds scary, but it only collides if two
contributors refactor the *same* function. At small contributor counts, the odds of
overlap are low.

### 4. Additive enum entries merge cleanly

`Item.HEAVY_DUTY_BOOTS` was added. Nothing else touched the enum. Git handled it as a
simple line addition. Even if Agent C had also added to the enum, the additions would
have landed in adjacent positions and 3-way merged.

## What would have caused actual conflicts

The test was "three agents, different mechanics, no coordination." For chokepoints to
actually bite, we'd need:

- **Two agents touching the same line** (e.g., both editing the exact same function body)
- **Two agents making incompatible refactors to the same helper** (A renames it, B
  changes its signature)
- **Two agents adding to the same enum with the same intended name** (trivial to
  resolve but a real textual conflict)
- **Two agents restructuring the same registry initialization** (e.g., both sorting
  it differently)

None of those happened organically across our three features. They're rarer than I
claimed.

## The real risk profile (updated)

### Low risk (empirically validated)
- Parallel item/move/ability additions
- Parallel new-event-type additions (if the renderer still uses `when`)
- Parallel refactors of different shared helpers

### Medium risk
- Two contributors adding moves with similar semantics (both reaching for the same
  move name or category)
- Refactors that change a function's signature (Agent A did this; if another agent
  had also edited `resolveEffect` body, collision was likely)

### Real risk (needs chokepoint fixes eventually)
- 5+ simultaneous contributors — the probability of overlap at any specific site rises
- Cross-cutting refactors (someone moving `ItemRegistry` to a different package while
  someone else adds a new item)
- Simultaneous registry reorganization + feature additions

## Implications for the chokepoint roadmap

Diary 043 and the user's priority call placed "engine chokepoints first" at the top
of the enabling-work roadmap. The empirical evidence says **the chokepoints are
latent, not acute**. At 1-3 contributors, git's merge algorithm handles our current
structure well enough that parallel feature work doesn't bite.

That doesn't make the chokepoint fixes wrong, but it updates the urgency:

- **Deferred until actual pain:** registry auto-registration, sealed-interface-for-enums,
  event-type rendering registry. These solve problems we aren't hitting.
- **Still worth doing early:** the cross-module / `@Serializable` / infrastructure
  work because they unlock downstream capabilities (analytics, JSON APIs, replay) that
  have value beyond parallelism.

## What the experiment taught me that I didn't expect

1. **Agents self-route around shared files.** C picked a `Move` field over a
   `MoveEffect` variant; that wasn't in the prompt. Good prompt engineering can
   probably steer this behavior even more deliberately.
2. **Runtime variance was small** (165s / 260s / 279s). Three agents in parallel
   finished in ~5 minutes total wall clock — faster than one agent doing all three
   features sequentially would have.
3. **Each agent wrote a diary entry and tests.** All three branches have clean
   commits with `ktlint + detekt` passing. Pre-commit-hook discipline transferred
   well to parallel work.
4. **Unexpected "correctness emergence":** Agent A noticed that Rapid Spin's Speed
   boost applies to the user, not the target, and introduced `UserStatBoost` as a
   new MoveEffect variant. That's a design choice I would have made solo; it came out
   of an independent agent without prompting.

## What to do with the stress-test branch

The user was explicit: we don't have to commit to the merge. The branch exists for
diagnostic purposes only. Two reasonable dispositions:

**Option 1 (adopted):** Delete `stress-test-merge` and the three worktree branches.
The features were diagnostic, not production. The diaries 044/045/046 they wrote can
stay if we like them, or be reimplemented after the chokepoint work if we prefer a
fresh baseline.

**Option 2:** Cherry-pick or re-apply the features cleanly onto `main` after
chokepoint work lands. The features themselves are real and useful (hazard removal,
Heavy-Duty Boots, multi-hit moves) and work; throwing them away means rewriting them
later.

My recommendation: **Option 2.** The features work, tests pass, code is clean. They
provide real functionality. Merging them to main is the pragmatic call. We didn't
have to merge, but given they all ship cleanly, there's no reason not to.

*(Leaving this decision for the user.)*

## Summary for future-me

If you come back to this wondering whether parallel agent work is viable in this
codebase: **yes, at 3 concurrent features it was clean, even with deliberate choke-
point targeting.** Git's 3-way merge is doing a lot of heavy lifting for us. The
structural chokepoints are real at scale but latent at our current size.

The enabling work (chokepoint fixes, module split, `@Serializable`) is still valuable
— but it's valuable for *other* reasons (discoverability, dep isolation, downstream
consumers), not because parallel work is currently painful.

## Related

- **Diary 043** — the prediction this tested
- **Diary 044 / 045 / 046** — the features the agents implemented (either adopted or
  rewritten after chokepoint work)
