# Diary 068: `:engine` public API audit via `internal` visibility

**Date:** 2026-04-14
**Status:** Complete — `internal` applied, `api` vs `implementation`
reclassified, all tests green.

## Why this diary exists

Diaries 065–067 established the module boundaries. What they didn't settle:
**what inside `:engine` is actually part of the contract, vs incidentally
public because Kotlin's default is `public`?** Today every top-level
declaration in `:engine` is callable from `:data`, `:render`, `:ai`,
`:cli`, `:analytics`, `:data-ingestion`. That's a large attack surface
for a module that's supposed to be the tight core.

The user's framing: *"To help enforce public APIs, would it help if
certain modules were not allowed to import each other, and could only
talk to each other through public interfaces?"* The Kotlin/Gradle lever
that gets us most of the way there: **`internal` visibility is per
Gradle module.** Anything marked `internal` in `:engine` is invisible
from `:cli` / `:data` / etc. — but still visible to `:engine`'s own
tests.

Separately — the user noted that getting the public interface wrong is
expensive; cheap to do now, expensive to reverse. So rather than a
mechanical mark-it-all-internal pass, this audit classifies every
top-level declaration and documents the decision.

## Method

1. Inventory external imports: grep `import com.pokemon.battle.*` from
   every non-`:engine` module. Produced 74 unique symbols imported from
   `:engine` packages (engine/, model/, loop/, phase/, gen/).
2. Walk every file in `engine/src/main/kotlin/`. For each top-level
   declaration, classify:
   - **Contract** — appears in the external import set. Stays `public`.
   - **Internal** — absent from external imports; only referenced inside
     `:engine`. Mark `internal`.
   - **Borderline** — flag for discussion (usually "technically public
     but nobody should be using it").
3. Apply `internal`. Compile. Tests.
4. Follow-up pass: re-classify `project(...)` dependencies as `api` vs
   `implementation` based on what's re-exposed transitively.

## Key finding from the inventory

Three whole packages are zero-external-import:

- `engine/item/*` — `ItemEffect` interface, `ItemRegistry`, and 10
  per-item effects. All call sites are inside `:engine` (phases,
  SpeedResolver, DamageCalc, HazardResolver). Per diary 066 these are
  destined to move to `:data` behind a registry DI, but until that
  happens they're legitimately engine-internal.
- `engine/ability/*` — same story. `AbilityEffect` interface,
  `AbilityRegistry`, 10+ per-ability effects. All internal callers.
- `gen/simplified/*` — three classes (`SimplifiedDamageCalculator`,
  `SimplifiedSpeedResolver`, `SimplifiedEndOfTurnPhase`). Only referenced
  from `engine/src/test/` — still inside `:engine`, so `internal` is safe.

Marking these three packages `internal` shrinks the engine's public
surface by roughly 30 files — all today leaking outward by default rather
than by design.

## Plan

- [x] External-import inventory.
- [x] Mark `engine/item/*` classes and top-level functions `internal`.
- [x] Mark `engine/ability/*` classes and top-level functions `internal`.
- [x] Mark `gen/simplified/*` classes `internal`.
- [x] Audit remaining single-file helpers in `engine/` package; mark
  `internal` where safe.
- [x] Run `./gradlew test ktlintCheck detekt` — green.
- [x] Follow-up: reclassify `project(...)` dependencies as `api` vs
  `implementation`.
- [x] Record the final "public API" list for future reference.

## What ended up `internal`

Three whole packages:

- **`engine/item/*`** — `ItemEffect` interface + `ItemRegistry` + 10
  per-item effects. Zero external callers. *Superseded by diary 071:
  interface and registry stay in `:engine` (now public), per-item
  effects moved to `:data/item/*`.*
- **`engine/ability/*`** — `AbilityEffect` interface + `AbilityRegistry`
  + 10+ per-ability effects. Zero external callers. *Same fate as
  items per diary 071.*
- **`gen/simplified/*`** — `SimplifiedDamageCalculator`,
  `SimplifiedSpeedResolver`, `SimplifiedEndOfTurnPhase`. Only referenced
  from `engine/src/test/`, which is same-module so `internal` is fine.

Plus single-file helpers in `engine/`:

- `HazardResolver.resolveHazardsOnSwitchIn`
- `SwitchOutClearing.resolveSwitchOutClearing`
- `MoveOrderResult` + `resolveMoveOrder`
- `DamageCalc.GenVDamageCalculator` + `calculateDamage` +
  `weatherDamageModifier` (the implementations; the `DamageCalculator`
  interface and `DamageResult` type stay public)
- `SpeedResolver.GenVSpeedResolver` (the default impl; interface stays
  public)
- `Ruleset`'s concrete objects (`NoGimmicksRuleset`,
  `PokemonChampionsRuleset`, `NationalDexRuleset`, `Gen9VgcTeraRuleset`)
  + `volatileBasedMoveLegality` helper (interface stays public)
- `TypeChart` interface + `StandardTypeChart` + `InverseTypeChart`
  (nobody constructs charts externally; `Effectiveness` and
  `typeEffectiveness` in `model/` remain the consumer surface)
- `defaultChanceCheck`

## What stayed `public` (the contract)

The types the compiler forced back to public by complaining about
"public function exposes internal type" — proof they're part of real
public signatures, not accidents:

- **Domain types** in `model/` — `Species`, `Pokemon`, `PokemonState`,
  `Move`, `Item`, `Ability`, `Type`, `Weather`, `Volatile`, `Side`,
  `Slot`, `StatusCondition`, `MoveCategory`, `MoveTarget`,
  `MoveEffect`, `FailReason`, `FieldState`, `Effectiveness`,
  `typeEffectiveness`, etc. *(Anything a consumer constructs or
  pattern-matches.)*
- **Engine events** — the full `BattleEvent` / `GameEvent` hierarchy,
  plus every concrete subclass (`DamageDealt`, `MoveAttempted`, etc.).
- **Pipeline contract** — `Phase`, `PhaseOutput`, `PipelineState`,
  `TurnResolution`, `TurnPipeline`, `TurnChoice`, `TurnChoices`,
  `InputRequest`, `InputResponse`, `BattleState`, `ChanceCheck`.
- **Extensibility interfaces** — `DamageCalculator`, `SpeedResolver`,
  `Ruleset`, `MoveLegality`, `DamageResult`. (Consumers *can't*
  implement these today without engine-internal knowledge, but their
  signatures appear in the concrete phases' constructors, so they have
  to be visible. This is the honest answer to "what would a custom
  gen need to implement?")
- **Loop contract** — `BattleLoop`, `BattleResult`, `ChoiceProvider`,
  `FaintReplacementProvider`, `InputResponder`.
- **Concrete phases** — `MoveOrderPhase`, `MoveExecutionPhase`,
  `EndOfTurnPhase`, `SwitchPhase`. Consumers construct these and pass
  them to `BattleLoop`.
- **Serialization** — `BattleEventJson`, `toJson`.
- **Switch-in helper** — `resolveSwitchInAbility` (used by
  `:ai/SideProviders`).

## `api` vs `implementation` pass

Three modules re-expose engine types in their public signatures:

- `:data` — `PokedexCatalog.CHARIZARD: Species` returns an engine type.
- `:render` — `TextRenderer.render(event: BattleEvent, ...)` takes
  engine types.
- `:ai` — strategies return `TurnChoice`, consume `BattleState`.

Changed these three from `implementation(project(":engine"))` to
`api(project(":engine"))` and added the `java-library` plugin (required
for the `api` configuration in Gradle). Consumers of `:data` / `:render`
/ `:ai` now see `:engine` transitively — which is correct because the
signatures already force them to.

`:engine` itself has no `project(...)` dependencies; question moot.
`:cli`, `:analytics`, `:data-ingestion` keep `implementation` since
nothing depends on them. `:cli` and `:analytics` declare
`implementation(project(":engine"))` directly because they use engine
types not exposed via the other modules — that's a correctness
declaration, not redundancy.

## Decisions I deliberately didn't make

- **I did not mark any `model/` types `internal`.** Every one is
  externally imported. The domain vocabulary is the contract.
- **I did not touch concrete-phase visibility.** They're externally
  imported for `BattleLoop` construction; they stay public. A future
  `:engine-api` split (diary 067's "separate repos" row) would need to
  revisit this.
- **I did not hide `resolveSwitchInAbility`.** Used by `:ai`, which is
  a legitimate external consumer.

## What this buys us

- External modules can no longer accidentally reach into engine
  registries (`ItemRegistry`, `AbilityRegistry`) or the per-item /
  per-ability effect classes. This was the diary 066 smell — phases
  call these directly — and marking them `internal` documents *"this
  is engine-internal today; the DI refactor is where this coupling
  becomes a public contract."*
- The `gen/simplified/*` package is no longer visible externally —
  consistent with the fact that only engine tests exercise it.
- The remaining public surface is now **intentional** rather than
  default-public. Any future PR that adds a new public type is a
  deliberate extension of the contract.

## What this costs

- If a future consumer wants to inject a custom item or ability
  effect, they can't — `ItemEffect` / `AbilityEffect` are `internal`.
  This is the exact DI pressure diary 066 flagged, so the right move
  when it arrives is the items-and-abilities-move-to-`:data` refactor,
  not reverting this audit.
- `:data` / `:render` / `:ai` now transitively re-expose all of
  `:engine`. A consumer of only `:render` still gets access to every
  `BattleState`, `Phase`, etc. That's usually desirable, but if we
  eventually want *smaller* consumer surfaces (a UI that only knows
  about `BattleEvent`, nothing else), we'd split `:engine` into
  `:engine-api` + `:engine-impl`. Diary 067's "separate repos" row
  is where that pressure gets recognized.

## Related

- **Diary 066** — the audit that motivated extracting `:render` and
  `:ai`. This diary does the same exercise at the *symbol* level.
- **Diary 067** — noted "separate repos" as the strongest isolation
  test. `internal` is our cheaper proxy until that pressure arrives.

## Validation signal

`./gradlew test ktlintCheck detekt` stays green. Plus: any external
module that now fails to compile names a declaration I misclassified —
either move it back to `public` or reorganize so the external caller
doesn't need it.

## Related

- **Diary 066** — the audit that motivated extracting `:render` and
  `:ai`. This diary does the same exercise at the *symbol* level instead
  of the *package* level.
- **Diary 067** — noted "separate repos" as the strongest isolation
  test. `internal` is our cheaper proxy until that pressure arrives.
