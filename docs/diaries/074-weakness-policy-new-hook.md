# Diary 074 — Weakness Policy: exercising the "new hook" path

**Status:** shipped

## Goal

Add the **Weakness Policy** held item to the engine. Mechanically: when the
holder is hit by a super-effective damaging move, Attack and Special Attack
each rise by 2 stages and the Policy is consumed.

The deeper purpose is to exercise `docs/skills/add-item.md` Extension 5a —
"your item needs a hook that doesn't exist yet." The existing
`onHolderTookDamage` hook exposes `state / holderSlot / attackerSlot /
damageDealt / abilities`, but not the *effectiveness* of the hit — which is
precisely the gate Weakness Policy needs.

## What shipped

One-line summary: Weakness Policy works end to end; `ItemEffect.onHolderTookDamage`
now threads `Effectiveness` so any future "react to super-effective hit" item
can reuse the same hook.

Files touched:

- `engine/src/main/kotlin/com/pokemon/battle/model/Item.kt` — add
  `WEAKNESS_POLICY`.
- `engine/src/main/kotlin/com/pokemon/battle/engine/item/ItemEffect.kt` —
  extend `onHolderTookDamage` with `effectiveness: Effectiveness`; annotate
  with `@Suppress("LongParameterList")` + rationale (7 params; detekt
  threshold is 6).
- `engine/src/main/kotlin/com/pokemon/battle/phase/MoveExecutionPhase.kt` —
  pass `result.effectiveness` from the per-hit damage pipeline into
  `onHitEvents`, then into the hook call.
- `data/src/main/kotlin/com/pokemon/battle/data/item/RedCardEffect.kt` and
  `RockyHelmetEffect.kt` — accept the new param and ignore it.
- `data/src/main/kotlin/com/pokemon/battle/data/item/WeaknessPolicyEffect.kt`
  — new. Emits `StatChanged(+2 ATK)`, `StatChanged(+2 SPA)`,
  `ItemConsumed(WEAKNESS_POLICY)` when `effectiveness == SUPER_EFFECTIVE &&
  damageDealt > 0`.
- `data/src/main/kotlin/com/pokemon/battle/data/Registries.kt` — register the
  new effect in the items block.
- `engine/src/test/kotlin/com/pokemon/battle/WeaknessPolicyTest.kt` — three
  mainline tests: super-effective fires (Flamethrower vs Venusaur), neutral
  does not (Tackle vs Venusaur), immune does not (Tackle vs Gengar).

No render-text file was added. Weakness Policy emits only existing event
types (`StatChanged`, `ItemConsumed`) — the default renderers are already
adequate. Per the skill doc step 6, render text is optional for items whose
events already render usefully.

## Hook-change decision: extend vs dedicated new hook

Two options:

1. **Extend `onHolderTookDamage` with an `effectiveness` parameter.** Every
   existing implementer must absorb the new param. Callers pass
   `result.effectiveness` from the damage pipeline.
2. **Add a new dedicated hook**, e.g. `onHolderTookSuperEffectiveDamage`,
   that fires only when effectiveness is `SUPER_EFFECTIVE`.

I chose option 1. Reasoning:

- **Effectiveness is orthogonal to "on hit."** The hook's job is already
  "react to the attacker damaging the holder." Effectiveness is additional
  *context* the hook needs to gate on, not a different *event*. Red Card and
  Rocky Helmet currently ignore it; Weakness Policy consults it.
- **Adding a dedicated hook would fragment dispatch.** The item registry
  would need to invoke multiple hooks per damage event and each impl would
  decide which to override. For a hypothetical future item that cares about
  *not-very-effective* hits (a bespoke "Absorb Bulb"-ish), we'd be up to
  three hooks for the same underlying event. One hook + a parameter keeps
  the dispatch surface small.
- **Information migration is one-directional.** Once `effectiveness` is on
  the hook, every implementer can access it. Removing it later is trivial
  (grep for the param, drop it). Going the other direction (item needs
  something not on the hook) requires a signature change again — which is
  what this diary is about, and we want to minimise repetitions.

Cost: one detekt `LongParameterList` bump, absorbed by an inline `@Suppress`
with rationale. The threshold stays tight for other sites. This matches the
project's tooling principle ("inline `@Suppress` with rationale over global
threshold changes").

## Was the skill doc (Extension 5a) accurate?

**Mostly yes.** The existing wording —

> Add the defaulted method to `ItemEffect` ... with a clear doc string,
> then wire it in at the one natural call site.

— covers the case where the hook genuinely doesn't exist. What it *doesn't*
cover is the more common intermediate case I hit here: **the hook exists but
is missing information**. In that situation:

- There is no "new defaulted method" to add — the method already has a
  default (no-op).
- You don't "wire it in at the one natural call site" because the call site
  already exists; you extend the argument list.
- Every existing override must be updated (a mechanical change, but worth
  flagging).

Friction points I hit:

1. **I had to re-derive that "extend vs add new hook" was even the
   decision.** The skill doc jumps to "add a new hook" without naming the
   extend-existing-hook path. A contributor could easily do the heavier
   rewrite when a parameter bump would do.
2. **The detekt `LongParameterList` threshold interaction.** Extending a
   hook that's already at 5 params pushes it over the threshold. The skill
   doc doesn't mention this at all; I discovered it only after running
   validation. Detekt's message was clear enough to fix, but the skill doc
   should warn that "extending a hook often tips detekt."
3. **Call-site threading.** The effectiveness lives on `DamageResult.effectiveness`
   in `MoveExecutionPhase.applyOneHit`. Plumbing it through `onHitEvents`
   required adding a parameter to a private helper as well. Not a large
   change, but not "the one natural call site" as the doc implies.

## Proposed updates to `docs/skills/add-item.md`

I have **not** applied these in this commit — leaving them to the main agent
to sequence with other skill-doc reviews. Concrete suggestions:

1. **Split Extension 5a into two sub-cases:**
   - **5a(i)** — *the hook doesn't exist at all.* Add a new defaulted method.
     (Current text.)
   - **5a(ii)** — *the hook exists but lacks a parameter your item needs.*
     Extend the existing signature; update every implementer to accept and
     ignore. Update the call site in the phase to pass the new value.
     Prefer this over adding a parallel hook when the new information is
     context, not a different event.

2. **Add a sentence to both sub-cases** noting that hooks with ≥6 parameters
   will trip detekt's `LongParameterList` rule. Recommendation: inline
   `@Suppress("LongParameterList")` with a one-line rationale, per the
   tooling principles in `CLAUDE.md`.

3. **Add a worked-example pointer.** Under "Related information → Worked
   examples," add:
   > *Extension 5a(ii) worked example:* diary 074 / `WeaknessPolicyEffect` —
   > extended `onHolderTookDamage` with `effectiveness` to gate on
   > super-effective hits.

4. **Clarify the Gotcha for `result.effectiveness`.** The damage calculator
   exposes `effectiveness` on `DamageResult`. Item hooks that want type-
   effectiveness context should consume that rather than recomputing from
   raw types — the calculator handles Freeze-Dry and future ability-gated
   immunities.

## Architectural note: effectiveness on the event vs on the hook?

A reviewer might reasonably ask "why isn't `effectiveness` on the
`DamageDealt` event?" — it already is. `DamageDealt(target, amount,
effectiveness, isCritical)` has carried it since before this change.

So why pass it as a hook parameter at all, rather than having the hook
inspect the emitted `DamageDealt`? Two reasons:

1. **Hooks don't see prior events within the same per-hit pass.** The hook
   fires from `applyOneHit` right after `DamageDealt` is emitted; the
   event is in the local `events` list but the hook's signature doesn't take
   it. Adding it would either mean passing the whole accumulated event list
   (a bigger blast radius than a single `Effectiveness` enum) or requiring
   hooks to filter the list themselves (more code per item).
2. **The hook is a pre-digested summary.** `damageDealt: Int` and
   `effectiveness: Effectiveness` are already the "what happened" that the
   hook cares about. Forcing each item to re-derive them from the event
   stream is the exact pattern diary 026 moved away from with the item
   registry.

Verdict: effectiveness on the hook is the right layer. The event carries it
for serialisation/audit; the hook takes a copy for dispatch.

## Validation

- [x] `./gradlew test` — all modules pass, including new `WeaknessPolicyTest`.
- [x] `./gradlew ktlintCheck` — clean.
- [x] `./gradlew detekt` — clean after the inline `@Suppress` on the hook.
- [x] `RegistryCoverageTest` passes — `WEAKNESS_POLICY` is registered.

## Feedback for the main agent

The three things the prompt asked for:

1. **Did the skill doc hold up?** Directionally yes. The gap is that
   Extension 5a conflates "new hook" with "extend hook signature" — the
   latter is the more common shape and was the actual path for Weakness
   Policy.
2. **Most important doc update:** split 5a into (i) new hook vs
   (ii) extended signature, and mention the detekt `LongParameterList`
   interaction. See section above for the exact proposed wording.
3. **Architectural concern:** none. Effectiveness belongs on the hook, not
   just the event. The hook is already a summary API; adding one more
   summary field is the cheap, correct move. If a future item needs some
   *other* dimension of the hit (move type? physical/special split?), the
   same "extend the hook, absorb the detekt bump" play applies — and is now
   paved.
