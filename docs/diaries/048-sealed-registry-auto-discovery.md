# Diary 048: Registry Auto-Discovery via Sealed Interfaces

**Date:** 2026-04-14
**Status:** Attempted and reverted — complexity outweighed the benefit at current scale

## Goal

Eliminate the shared-file chokepoint in `ItemRegistry`, `AbilityRegistry`, and
`ItemTextRegistry` — the `listOf(…)` blocks that every new entity edits. Use Kotlin's
`sealed interface` feature to auto-discover registered entities at class-load time.

From diary 043's chokepoint list, this is engine chokepoint #1.
From diary 047, the chokepoint is latent (not actively hurting at 1-3 contributors),
but fixing it compounds: every future item/ability ships without touching a shared
registry file.

## Why sealed interface is the right tool

Kotlin's `sealed interface` enforces two things at compile time:
1. All implementations must be in the same Gradle module
2. All implementations are known to the compiler — `KClass.sealedSubclasses` returns
   a complete list

For our use case — a catalog where every implementation is a singleton `object` in
one module — that's perfect. We get:
- **Zero runtime reflection libraries** (no classgraph, no KAPT, no KSP)
- **Zero META-INF files** (no ServiceLoader)
- **Zero build-time code generation** (no custom Gradle tasks)
- **Zero shared-file edits** — new entity = new file, that's it
- **Compile-time exhaustiveness is preserved** for any `when (item)` we still want

The standard auto-discovery idiom:

```kotlin
sealed interface ItemEffect { val item: Item; /* hooks */ }

object LeftoversEffect : ItemEffect { … }   // each in its own file
object FocusSashEffect : ItemEffect { … }
// etc.

object ItemRegistry {
    private val effects: Map<Item, ItemEffect> =
        ItemEffect::class.sealedSubclasses
            .mapNotNull { it.objectInstance }
            .associateBy { it.item }
}
```

`.objectInstance` returns the singleton for `object` declarations, null for classes.
Private helper classes like `ChoiceItem` (used as a `by`-delegate) are naturally
filtered out.

## Scope

### Convert to sealed
- [ ] `ItemEffect` → `sealed interface`
- [ ] `AbilityEffect` → `sealed interface`
- [ ] `ItemText` → `sealed interface`

### Auto-populate registries
- [ ] `ItemRegistry.effects` computed from `ItemEffect::class.sealedSubclasses`
- [ ] `AbilityRegistry.effects` computed from `AbilityEffect::class.sealedSubclasses`
- [ ] `ItemTextRegistry.texts` computed from `ItemText::class.sealedSubclasses`

### NOT in scope (and why)

- **`AbilityText`** — has a `GenericAbilityText(ability)` fallback instantiated per
  ability in `AbilityTextRegistry.textFor(ability)`. That's a dynamic instance, not a
  compile-time object, so sealed doesn't fit cleanly. And there are only 2 registered
  entries; the chokepoint is negligible. Leave as regular interface.
- **`MoveDex`** — moves are data (`Move` data class), not interfaces. The sealed trick
  doesn't apply. A `sealed interface MoveDefinition` wrapper per move would be
  boilerplate churn for minimal gain. Leave as `val X = register(Move(…))` pattern.
- **`StatusCondition`**, **`Side`**, **`SideCondition`**, **`SideHazard`**,
  **`Volatile`**, **`Type`**, etc. — these are pure identity enums or sealed hierarchies
  already; no "registry" to auto-populate.

## Kotlin semantics to verify

- `sealed interface` subtypes: must be in the same module (✓ our effects are)
- `KClass.sealedSubclasses`: returns *direct* subclasses only. Our hierarchies are
  flat (no effect extends another effect except via `by`-delegation to a private helper
  class, which is fine — the helper class appears in `sealedSubclasses` but has no
  `objectInstance`, so `mapNotNull` filters it out)
- `KClass.objectInstance`: returns null for classes, the instance for `object`.
  Available in the Kotlin stdlib — no `kotlin-reflect` dependency needed for these
  two APIs specifically (per the Kotlin docs; verify at implementation time)

If `kotlin-reflect` turns out to be needed for `objectInstance`, add it to the build
(it's a small library, widely used, no concerns). Prefer not to add deps if we can
avoid them.

## What this unlocks

After this change, adding a new item:
1. Create `MyNewItemEffect.kt` in `engine/item/` with `object MyNewItemEffect : ItemEffect { … }`
2. Add `MyNewItem` to the `Item` enum
3. (Optional) Create `MyNewItemText.kt` in `render/item/`
4. Done — no `ItemRegistry` edit, no `ItemTextRegistry` edit

Two file edits (enum + effect) instead of four. And no file-level merge conflict risk
for the registries.

## Potential downsides

### Adding a sealed-interface restriction

Once `ItemEffect` is sealed, external modules can't contribute implementations. For
our single-module codebase this is a non-issue. If future gen-specific modules want
to ship their own items (diary 041's multi-module vision), they'd need either:
- A separate sealed interface per gen (`GenVItemEffect`, `GenIVItemEffect`) with
  composition in the registry
- Or we relax the sealed constraint at that point

Both are tractable. Leaving as a "flag this when it matters" concern.

### Discoverability changes

With `listOf(Leftovers, FocusSash, LifeOrb, …)` you could grep the registry file for
"what items exist." With auto-discovery, the answer is "whatever's in `engine/item/`"
— structurally the same answer, but requires looking at a directory vs a file.

IDEs handle this fine (Kotlin tooling shows all sealed subclasses on hover). For
command-line: `ls src/main/kotlin/com/pokemon/battle/engine/item/*Effect.kt`.

### Class load ordering

`KClass.sealedSubclasses` is deterministic per Kotlin compiler version but the *order*
isn't guaranteed to match any source/file ordering. Since our registries key by enum
value (not position), order doesn't matter.

## Plan

### Step 1: Convert `ItemEffect` to sealed; auto-populate `ItemRegistry`
- [ ] Change `interface ItemEffect` to `sealed interface ItemEffect`
- [ ] Update `ItemRegistry.effects` to use `ItemEffect::class.sealedSubclasses.mapNotNull { it.objectInstance }.associateBy { it.item }`
- [ ] Build + test (221 tests → 236 tests pass unchanged)
- [ ] Delete the manual `listOf(LeftoversEffect, FocusSashEffect, …)` from ItemRegistry

### Step 2: Same for `AbilityEffect` / `AbilityRegistry`
- [ ] Convert to sealed
- [ ] Auto-populate
- [ ] Verify all ability tests still pass

### Step 3: Same for `ItemText` / `ItemTextRegistry`
- [ ] Convert to sealed
- [ ] Auto-populate
- [ ] Verify rendering tests still pass

### Step 4: Verify the class-load cost
- [ ] Check that `./gradlew test` still completes in similar wall-clock time (not a
      regression from sealedSubclasses scanning)

### Step 5: Document the pattern
- [ ] Add a section to `CLAUDE.md` or `architecture.md`: "Adding a new item/ability:
      one file, no registry edit"
- [ ] Update the registry Lesson Learned in architecture.md with "auto-discovered"
      language

## Validation criteria

- All 236 tests pass (behavior-preserving refactor)
- ktlint + detekt clean
- `ItemRegistry` and `AbilityRegistry` have no per-entity references (just the
  type-based lookup)
- `ItemTextRegistry` follows the same pattern
- Adding a new item in a follow-up diary requires zero registry edits

## Related

- **Diary 026** — item registry (original pattern being enhanced)
- **Diary 027** — ability registry (parallel pattern)
- **Diary 038** — render separation (introduced ItemText registry)
- **Diary 043** — chokepoint analysis (this is fix #1)
- **Diary 047** — empirical finding that chokepoints are latent; this diary makes
  them structurally impossible

---

## Postmortem: we tried this and reverted

After implementing the full plan (3 sealed interfaces + 3 auto-populated registries +
kotlin-reflect dep) and getting all 236 tests passing, the user flagged a real concern:
**what is the exact chokepoint we're solving?**

Going back to source: diary 043 identified the chokepoint as "merge conflicts on
`listOf(…)` when two contributors add items in parallel." Diary 047's empirical test
(three subagents in parallel) found that git's 3-way merge handles this fine — zero
conflicts across three features that all touched shared registries.

So the chokepoint that motivated this diary **did not actually bite in practice**.
And the fix introduced real costs:

- **Silent-failure class** — if an effect is accidentally a `class` instead of `object`,
  or doesn't implement the sealed interface, it's silently missing from the registry.
  Before: compile error. After: runtime partial initialization.
- **Lost compile-time guarantee** — registry wiring moved from compile-time to class-load.
- **Kotlin-reflect dep** — small but a real addition; `KClass.sealedSubclasses` and
  `.objectInstance` require it at runtime (despite being in the `kotlin.reflect` stdlib
  namespace, the implementation lives in the `kotlin-reflect` artifact).
- **Readability cost** — `ItemEffect::class.sealedSubclasses.mapNotNull { it.objectInstance }.associateBy { it.item }`
  is two levels of indirection compared to `listOf(…)`.

## Reverted via `git restore`

None of the sealed changes had been committed. `git restore` brought the files back to
HEAD cleanly. No history pollution, no revert commit.

## Lessons

1. **Verify the chokepoint is biting before building for it.** Diary 043 named the
   chokepoint; diary 047 tested it empirically; this diary tried to fix it. The
   ordering was right but the go/no-go decision was premature. The empirical test in
   047 already showed "chokepoints are latent, not acute" — that should have cooled
   the urgency, not led straight to a fix.

2. **Every abstraction has a complexity budget.** We bought "adding an item is one
   file" and paid with silent-failure risk + a dep + readability. The exchange rate
   wasn't favorable at our scale.

3. **Git's 3-way merge is smarter than you give it credit for.** Parallel additions
   to append-mostly files (`listOf(a, b, c)` with each contributor adding one line)
   merge cleanly. Don't design around a problem that git already solves.

4. **"Revisit when it bites" is a valid plan, not a cop-out.** The sealed/auto-discovery
   approach is documented here; when/if contributor count or item count makes the
   manual registry painful, the path is known. No work lost.

## What stays in the codebase

- **`listOf(…)` registries** — unchanged from before the experiment
- **Documentation comments on `ItemRegistry` / `AbilityRegistry`** — updated to
  reference this diary ("we evaluated and rejected auto-discovery at current scale")
- **No `kotlin-reflect` dep** — reverted
- **This diary** — the postmortem is valuable record even though the code isn't

## Revisit triggers

Build the sealed-auto-discovery variant when ANY of these hit:
- Registry list exceeds ~50 entries (readability starts to matter)
- We actively encounter merge conflicts on registry files (diary 047's result was
  zero; track this)
- We onboard a 4th+ concurrent contributor (parallelism pressure rises)
- A gen-specific module plan lands that needs per-gen registries (sealed composition
  is the natural fit there)

## Related

- **Diary 043** — identified the chokepoint
- **Diary 047** — empirically showed the chokepoint is latent (the key data point we
  undervalued in launching this diary)
