# Diary 064: Data as code vs data as resource — a deliberate parallel implementation

**Date:** 2026-04-14
**Status:** Both implementations landed (2026-04-14). `Pokedex.loadJsonFromClasspath` (runtime resource path) and the generated `PokedexCatalog` (compile-time symbol path) coexist; tests pass for both. `:data-ingestion:codegenSpecies` regenerates the catalog after any ingestion refresh.

## The setup

We have 98 species ingested from PokeAPI sitting in
`engine/src/main/resources/pokedex/species/<slug>.json` plus an `index.txt`
manifest. The runtime loader is `Pokedex.loadJsonFromClasspath()`. This is one
viable shape for "feeding ingested data back into the engine."

A different shape: **generate Kotlin source** at ingestion time. Each species
becomes a `val CHARIZARD = Species(name = "Charizard", types = listOf(...), ...)`
in a generated `PokedexCatalog.kt` file. The engine consumes symbols, not
strings.

The user's framing: *keep both as a case study, as long as we maintain
codebase isolation.* This diary captures the comparison and the isolation
design.

## Why this is worth a diary, not just a feature commit

The two paths produce the same domain output (`Map<String, Species>` or the
equivalent). They differ in the **shape of the consumer interface**:

- JSON loader: `pokedex.getValue("Charizard")` — string lookup
- Generated Kotlin: `PokedexCatalog.CHARIZARD` — symbol reference

That tradeoff appears throughout software (config files vs. constants, schema
vs. generated DTOs, dynamic registries vs. compiled enums). Spelling it out for
*this* codebase, with both implementations side-by-side, gives us a place to
point when the same question recurs (moves, items, abilities — all of which
could go either way).

## Comparison across honest dimensions

### Compile-time safety

- **Generated Kotlin wins.** `PokedexCatalog.CHARRIZARD` is a compile error
  pointing at the typo. `pokedex["Charrizard"]` returns null and surfaces
  whenever the consumer dereferences it.
- For 98 species growing to thousands, the cost of "find the typo at runtime"
  compounds linearly; the cost of "compile error" is constant.

### Readability

- **JSON wins for browsing data.** `ls .../species/` shows 98 alphabetized
  files; one file is one species in 12 self-explanatory lines. Diffing a stat
  change is a one-line git diff.
- **Generated Kotlin wins for reading consumer code.** `Pokedex.CHARIZARD.baseHp`
  reads as a noun lookup; the JSON path reads as an indirection (`is this
  string actually a key?`).
- **Roughly tied — different reading tasks.**

### Debuggability

- **Generated Kotlin wins on fail-fast.** Typos die at compile time. Find-usages
  works precisely because the IDE knows what's a species reference vs. a string
  match.
- **JSON wins on data inspection.** "What's wrong with Pikachu?" → open
  `pikachu.json`, see immediately. The generated path requires either jumping
  into a thousand-line catalog file or back to the source JSON.
- **JSON also wins on iteration loop.** Edit a stat, re-run a test, see the
  effect — no recompile.
- **Slight edge to generated for typo detection; JSON for data triage.**

### Developer ergonomics / experience

- **Generated Kotlin wins decisively.**
  - **Autocomplete on `Pokedex.`** lists all 98 species — instant
    discoverability for someone writing a new test or play scenario. JSON
    string keys are opaque to the IDE.
  - **Find-usages and rename refactor** propagate correctly.
  - **Jump-to-definition** lands on the actual data declaration.
- **JSON wins on lower-friction data evolution** — no recompile cycle to
  refresh.
- **Big edge to generated for IDE workflows; small edge to JSON for "I'm
  iterating on data."**

### Synthesis

Generated Kotlin is the better fit *for this codebase* because:
- `MoveDex` is already hand-written Kotlin (`MoveDex.FLAMETHROWER = Move(...)`).
  Catalog-of-named-things is the project's existing pattern; species being
  JSON-loaded is the inconsistency.
- "Name the concept" (architecture.md Lessons Learned) applies — symbols *are*
  names; strings are guesses about names.
- Most of the use cases are dev-write-time (writing tests, building play
  scenarios) where autocomplete pays off vs. dev-runtime (iterating on data
  shape) where JSON pays off — and shape is stable, data-iteration is rare.

But the JSON loader has its own real value: better for inspecting / diffing
data, and the lower friction is genuine when something IS being tweaked. The
existence proof of "this works as a runtime classpath load" is also
methodologically useful (we now know the pattern, can apply it elsewhere).

## Why keep both

Three reasons the user is right to want them side-by-side:

1. **Case study payoff.** When the same "data as code vs data as resource"
   question hits items, abilities, moves, or ingested Smogon stats, this
   diary + the two implementations let us point at concrete prior art
   instead of redoing the analysis.
2. **Different tools for different jobs.** Test code with hand-built fixtures
   + dev iteration: JSON is friction-free. Production play setups + IDE work:
   generated symbols win.
3. **Architectural validation.** If the engine was poorly factored, swapping
   between the two consumption patterns would require engine surgery. It
   doesn't — both shapes converge on `Species` domain values. That's a real
   property of the existing layering worth preserving as a checkpoint.

## Codebase isolation design

For "isolated" to mean something concrete:

### File / namespace separation

| Path | Role | Generated? |
|------|------|------------|
| `data/Pokedex.kt` | Runtime loaders (CSV, JSON dir, JSON classpath) — original | Hand-written |
| `data/PokedexCatalog.kt` | Generated `object` with 98 `val <SLUG> = Species(...)` | Yes — header marker |
| `engine/src/main/resources/pokedex/species/*.json` | Source of truth ingested from PokeAPI | Yes (ingestion writes) |
| `engine/src/main/resources/pokedex/species/index.txt` | Manifest, used by JSON classpath loader | Yes (ingestion writes) |
| `engine/src/test/.../PokedexJsonLoaderTest.kt` | Tests for the JSON loader path | Hand-written |
| `engine/src/test/.../PokedexCatalogTest.kt` | Tests for the generated catalog | Hand-written |

The generated file has a `// Generated by :data-ingestion:codegen — do not edit.`
header. Editing it is a re-run-codegen action.

### Build separation

- The JSON loader code compiles independently of the catalog and vice versa.
- The codegen step is a separate Gradle task (`:data-ingestion:codegenSpecies`),
  not wired into compileKotlin. Running ingestion produces *both* the JSON
  resource files (for the loader) and the regenerated `PokedexCatalog.kt`
  (for the symbols).
- Removing either path is one file deletion + a few callers — no downstream
  damage.

### Consumer choice

A test or play setup picks **one** path explicitly:

```kotlin
// JSON loader path (string-keyed, runtime resolution):
val pokedex = Pokedex.loadJsonFromClasspath()
val charizard = Pokedex.charizard().getValue("Charizard")

// Generated catalog path (symbol-keyed, compile-time resolution):
val charizard = PokedexCatalog.CHARIZARD
```

Mixing the two in one consumer is allowed but discouraged — pick a style per
file. (The architecture doesn't enforce; we lean on convention.)

## Plan

1. Add `PokedexCodegen` under `:data-ingestion` that reads
   `engine/src/main/resources/pokedex/species/index.txt` + each `<slug>.json`
   and emits `engine/src/main/kotlin/com/pokemon/battle/data/PokedexCatalog.kt`
   with one `val <SLUG_UPPER_SNAKE> = Species(...)` per species.
2. Register Gradle task `:data-ingestion:codegenSpecies`.
3. Run it; commit the generated file.
4. Add `PokedexCatalogTest` asserting at least one well-known species (Pikachu)
   resolves to the right base stats.
5. Both paths green; keep both.

## Learnings (the explicit part)

- **The "name the concept" instinct, taken seriously, points at symbols over
  strings.** Codegen is one of the two ways to materialize a name; the other is
  hand-writing. Both beat a string lookup.
- **"Compile-time safety" is the smallest thing generated code buys you.** The
  bigger wins are IDE-driven: autocomplete, find-usages, refactor. Listing
  those as separate dimensions in the comparison made the case stronger than
  pitching just safety.
- **Architectural swappability is testable.** Building both implementations of
  a data-consumption interface is a concrete check that the engine doesn't
  secretly couple to the consumption shape. If swapping had required engine
  edits, the layering was wrong. (It didn't; the layering held up.)
- **Some patterns deserve to be plural.** The reflex toward "one obvious
  way" is usually right (sealed events, single state type), but for the
  data-consumption boundary, two paths each pay off in different contexts and
  the cost of maintaining both is small.
- **Process learning**: the "generated Kotlin" alternative was your
  suggestion, not in my initial implementation pass. I fixated on the JSON
  loader because that was the visible gap — the manifest, the classpath
  resource resolver, all the obvious "missing infrastructure" pieces. The
  alternative was a frame-shift, not an infrastructure piece. Worth flagging:
  *when the obvious next step is "infrastructure for the existing approach,"
  ask once whether the approach itself is right.* This is preflight rule #2
  ("tool-match for the change shape") generalized — sometimes the right tool
  is a different shape, not a better hammer.

## Related

- **Diary 041** — established the JSON ingestion pipeline (the data this
  consumes).
- **Diary 058** — naming review; "name the concept" framing applies here.
- **Diary 026** — the registry pattern for items. Same family of question:
  catalog of named things, how do callers reach them? Items chose the registry
  path; species now have both JSON-loaded and generated-catalog paths.
- **Architecture.md Lessons Learned**: "Name the concept, not the
  implementation." This diary is that lesson applied at the data-consumption
  boundary.
