# Diary 053: Module Split — Engine as Its Own Module

**Date:** 2026-04-14
**Status:** Complete

## Goal

Restructure the repo so `engine/` is a standalone Gradle module. Current source layout
is single-module (everything at `src/`). After this diary, source lives at `engine/src/`
with its own `engine/build.gradle.kts`. No behavior changes; no code changes; just
layout.

This is the plumbing-only step. Subsequent diaries will add sibling modules
(`data-ingestion/`, analytics, clients). Getting the split right once means those are
cheap additions.

## Why now

Motivation is diary 041's multi-module vision:
> The engine module has zero I/O and zero serialization deps.

Today we have one module containing everything. When we add a CLI, a web UI, a data
ingestion tool, or any client with its own dependencies, those deps leak into the
engine's build graph unless we structure this now. Doing it at 0 clients is easy; at
3 clients it's a painful retrofit.

## Current state

- Root `build.gradle.kts` with plugins, deps, kotlin block, ktlint config, detekt
  config, jacoco config
- `src/main/kotlin/com/pokemon/battle/...` (everything)
- `src/main/resources/data/species.csv`
- `src/test/kotlin/com/pokemon/battle/...`
- `docs/` at root (stays)
- `.githooks/pre-commit` runs `gradlew ktlintCheck detekt` (may need updating)
- Fresh baseline (diary 052 measurements): ~5s warm full test, ~15s cold

## Target state

```
pokemon-battle/
├── settings.gradle.kts        — declares :engine module
├── gradle.properties          — parallel / caching / config-cache settings
├── build.gradle.kts           — minimal; maybe subprojects block
├── engine/
│   ├── build.gradle.kts       — the current build config (plugins, deps)
│   └── src/
│       ├── main/kotlin/com/pokemon/battle/...
│       ├── main/resources/data/species.csv
│       └── test/kotlin/com/pokemon/battle/...
├── docs/                      — unchanged
├── .git/                      — unchanged
├── .githooks/                 — may need command updates
└── CLAUDE.md / CONTRIBUTING.md / architecture.md — unchanged, paths stable
```

## Plan

### Step 1: Freeze-and-measure baseline

Capture current perf numbers pre-change to compare against post-split. Already have
from diary 052's retro:
- Warm incremental `test`: 0.6s
- Warm `test --rerun-tasks`: 5s
- Cold `test --rerun-tasks`: 15s
- Pre-commit combo (`ktlintCheck detekt` warm): 5s

### Step 2: Create settings.gradle.kts

```kotlin
rootProject.name = "pokemon-battle"
include(":engine")
```

### Step 3: Add gradle.properties

```properties
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
```

### Step 4: Move source tree

```bash
mkdir engine
git mv src engine/src
```

Git tracks the rename; history is preserved via `git log --follow <file>`.

### Step 5: Split build.gradle.kts

Move most of the current root `build.gradle.kts` into `engine/build.gradle.kts`:
- Plugins (kotlin jvm, application, ktlint, detekt, jacoco)
- Dependencies
- Kotlin toolchain
- Task configurations (test, jacocoTestReport)
- `application { mainClass.set("MainKt") }`
- `detekt` config block

Root `build.gradle.kts` becomes minimal — possibly empty or just a comment. Could add
`allprojects { repositories { mavenCentral() } }` if we prefer centralized repos, but
better to keep module self-contained for now.

### Step 6: Verify builds

- `./gradlew :engine:test` from root — should work
- `./gradlew test` — will this run `:engine:test` too via task forwarding? Usually yes
  for single-module roots. Let me check during implementation.
- IntelliJ re-import (if applicable — we probably aren't actively running IntelliJ, but
  mentally: the project imports cleanly)

### Step 7: Update pre-commit hook if needed

Check `.githooks/pre-commit` — if it runs `./gradlew ktlintCheck`, does that still
work with multi-module? It should, because `ktlintCheck` aggregates across subprojects
when the plugin is applied at the engine level. Verify.

### Step 8: Measure post-split speeds

Same scenarios as Step 1. Expect:
- Warm incremental: still <1s (config-cache hit should keep this fast)
- Warm `--rerun-tasks`: similar to before (same work, minor config overhead)
- Cold: similar (maybe +0-1s for extra config evaluation)

If any is >50% worse, investigate before committing.

### Step 9: Update docs

- `CLAUDE.md` — update any `./gradlew test` references to note `./gradlew :engine:test`
  also works. Probably leave the short version as primary.
- `CONTRIBUTING.md` — same
- `architecture.md` — may reference the module layout; check
- `.gitignore` — may reference `build/`; should still work since each module has its
  own `build/`

### Step 10: Commit + verify pre-commit hook still fires

Pre-commit hook runs ktlint + detekt. Make sure the commit succeeds without errors.

## Acceptance criteria

- `./gradlew :engine:test` passes with 237 tests
- `./gradlew test` still works (root-level test task)
- Pre-commit hook fires ktlint + detekt and passes
- Warm incremental test stays under 1s
- Warm `--rerun-tasks` stays under 7s (accepting ~2s config overhead)
- IntelliJ re-imports cleanly (manual check)

## Risks and mitigations

### Risk: IDE re-import churn
Mitigation: commit as a clean plumbing change; note in commit message for any future
IntelliJ user that re-import is needed.

### Risk: Pre-commit hook breaks
Mitigation: run `.githooks/pre-commit` manually before committing to verify.

### Risk: Config-cache incompatibility with ktlint or detekt plugins
Some older plugin versions don't support configuration-cache. If this breaks, disable
`org.gradle.configuration-cache` for now and leave `parallel` + `caching` on. Note in
diary.

### Risk: `src/main/resources/data/species.csv` path references break
The CSV is loaded via `Pokedex::class.java.classLoader.getResourceAsStream("data/species.csv")`.
Classpath resolution is unchanged by module split; the file moves to
`engine/src/main/resources/data/species.csv` but its classpath position stays
`data/species.csv`. Should just work — verify in Step 6.

## What this does NOT do

- Does not add a second module (diary 041 has the `data-ingestion/` module plan)
- Does not change any public types or APIs
- Does not split engine behavior from rendering (`render/` stays inside engine module;
  that's diary 038's concern and it's resolved)
- Does not affect how diary-driven workflow operates

## Expected follow-ups

- **Diary 054+ (data-ingestion module)** — once engine is an island, adding
  data-ingestion as a sibling module is mostly `settings.gradle.kts` + one new
  directory
- **Configuration-cache incompatibilities** — if surfaced, narrow diary to track them

## Related

- **Diary 041** — multi-module vision (the `why`)
- **Diary 052** — current perf baseline
- **Diary 043** — dependency direction principles
