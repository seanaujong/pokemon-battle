# Diary 086: Python battle-log reader + `Side.name` invariant test

**Date:** 2026-04-14
**Status:** Complete.

## Why this diary exists

Two ships in this session, shipped together because they're the same
"close out flagged items" shape:

1. **`Side.name` invariant test.** Diary 082 flagged it; diary 085 made
   it more load-bearing by duplicating the `"SIDE_1"`/`"SIDE_2"`
   convention across Kotlin + SQL + script. Fixing it now finally gets
   the mechanical enforcement the cross-language consumers depend on.

2. **Python battle-log reader** (`scripts/battle-log.py`). A second
   constructed forcing function for "the JSON IS the contract,"
   following diary 085's Kotlin-script isolation test but in Python
   — the language farthest from Kotlin that our real use cases touch
   (the Python smoke test, ML training loops from diary 081's
   industry comparison).

Both ship as one diary because each is small on its own and they
answer the same question in different directions: **are the cross-
language consumers we claim to support actually functional?**

## Ship 1: `PlayerTagsKeyConventionTest`

Three assertions in `:persistence`:

- `Side.SIDE_1.name` equals `"SIDE_1"`, `Side.SIDE_2.name` equals
  `"SIDE_2"`. If someone renames the enum, this fires — catching the
  drift before it breaks the SQL cookbook or the analyst script.
- Reading `metadata.playerTags[Side.SIDE_1.name]` equals reading
  `metadata.playerTags["SIDE_1"]`. Round-trip parity between Kotlin
  callers and non-Kotlin consumers.
- `Side` values have unique, non-blank names.

The test lives in `:persistence` because that module owns the
convention as part of the on-disk format contract.

## Ship 2: `scripts/battle-log.py`

Reads a `PersistedBattle` JSON file (or directory — takes the first
file alphabetically as a smoke test), walks the event stream, prints
a turn-by-turn transcript:

```
--- Turn 5 ---
  [order       ] order: SIDE_1 → SIDE_2
  [move        ] SIDE_1 used Nasty Plot
  [stat        ] SIDE_1: SPECIAL_ATTACK ↑2
  [move        ] SIDE_2 used Thunderbolt
  [damage      ] SIDE_1 took 97 (super_effective)
  [FAINT       ] SIDE_1 fainted
```

Roughly 150 lines of Python. No state reconstruction (HP tracking would
duplicate `apply()` logic in Python — the exact re-port this diary is
specifically not doing). Just: parse JSON, walk the arrays, label
each event.

**Real value:** an analyst who doesn't want to install Kotlin or run
gradle can still inspect any saved battle on any laptop with a Python
install. Debugging a weird AI outcome is now `python3 scripts/battle-log.py
battles/<uuid>.json` instead of "re-run the matrix runner with logging
enabled and hope you hit the same seed."

## What both ships tested

The two ships validate different architectural claims:

- The invariant test validates **internal consistency**: the
  convention-string `"SIDE_1"` does actually equal `Side.SIDE_1.name`.
  Without this, the SQL cookbook and analyst script would drift
  silently on enum renames.
- The Python reader validates **cross-language isolation**: the event
  stream is self-describing enough that a Python consumer renders it
  without any engine logic. No state reconstruction, no `apply()`
  semantics replicated. If this worked, the format is the contract.

Both did work. The matrix corpus from diary 084 passed through both
paths this session — the invariant test stays green, the Python
renderer produced a clean 8-turn transcript of a real TypeAI-wins-
after-losing-first battle.

## Code review

### Diagnostics

- *Testable:* the invariant test is itself the test. The Python
  renderer is exercised by running it against the corpus — no
  automated CI gate, but the output is self-verifying (a human can
  read and spot errors).
- *Readable:* the invariant test is three short methods, each with
  one assertion per concern. The Python renderer uses a lookup table
  (`EVENT_LABELS`) + a small `describe()` dispatch; new event types
  fall through to a generic `"{kind}: {fields}"` line instead of
  crashing.
- *Layer:* invariant test lives in `:persistence` because the
  convention *is* part of the persistence format contract. Python
  renderer lives in `scripts/` because it's a dev-tooling artifact,
  not shipped code.
- *Auditable:* the Python script's transcript is the audit — every
  event in the JSON produces exactly one line.
- *Happy path:* JSON in, transcript out.
- *Failure modes:* unknown event types render as `"{kind}: {fields}"`
  — visible, not silent. Missing file → error exit with a clear
  message. Malformed JSON → Python's native traceback (acceptable
  for a dev script; not user-facing).
- *Duplicated logic:* the `EVENT_LABELS` dict duplicates part of
  `BattleEventJson`'s sealed-variant catalog. Intentional — the
  Python script is meant to be standalone. If an event variant is
  added in Kotlin and not here, the Python script prints the short
  class name as its label, which is still informative. No silent
  failure.
- *Illegal state:* none.
- *Invariants:* the script assumes the `type` discriminator's short
  form is the DTO class name (e.g. `MoveAttemptedJson`). Same
  assumption as the SQL cookbook; flagged in
  `docs/corpus-format.md`. If we ever add `@SerialName` short names,
  both scripts + the cookbook need a coordinated update.
- *Mutation:* none.
- *Names:* `short_type` vs `describe` vs `label` — one parses the
  discriminator, one renders the body, one picks a display tag.
  Distinct roles.
- *Layer-blur:* none — neither ship touches `:engine`.
- *Removal:* invariant test is one file in `:persistence/src/test`,
  deletable without impact. Python script is one file in `scripts/`,
  deletable without impact on the live pipeline (just loses a debug
  tool).
- *Other:* the Python script's shebang (`#!/usr/bin/env python3`) +
  executable bit mean it works both as `python3 scripts/battle-log.py`
  and as `./scripts/battle-log.py`. Matches the existing
  `smoke-test-external-client.py` pattern.

### Industry comparison

- **Invariant tests** (checking that string conventions match
  compile-time constants) are a common pattern in
  frontend/backend-split teams — "is this JSON key stable?" tests.
  Spring Boot has `@JsonProperty` + reflection-based tests for the
  same problem; we're not using reflection because the surface is
  small (one enum).
- **Language-agnostic JSON event replay** is how Pokemon Showdown's
  replay-viewer system works. Showdown emits `|…|` lines (their
  battle protocol); consumers in TS, Python, Rust all parse them
  without re-porting the engine. Ours is JSON-structured instead of
  Showdown's text protocol, but the principle — the wire format is
  the stable interface — is shared.
- **What we're not shipping:** an HTML-based replay viewer with HP
  bars, animations, move sprites. That's the Showdown replay UI.
  Real work, real value eventually, but massively out of scope for
  a session focused on "prove the contract is consumable." Would
  force KMP-adjacent questions from diary 073 and frontend-repo-
  split questions from diary 067. Parked.

### Findings to fix

None urgent. The "when event DTOs add short `@SerialName` names,
update Python + SQL cookbook" dependency is noted in
`docs/corpus-format.md`; we'll catch drift the first time someone
runs the scripts after such a change.

## Validation

- `./gradlew test ktlintCheck detekt` green.
- `python3 scripts/battle-log.py battles/` produces an 8-turn
  transcript of a real matrix battle with clean labels and state
  mostly visible to a reader.
- `DiaryConventionTest` passes (this diary has the `## Code review`
  section).

## Related

- **Diary 082** — original flag for the invariant test.
- **Diary 085** — duplicated the playerTags convention across
  languages, making the test earn its keep.
- **Diary 081** — industry comparison; Showdown's replay viewer is
  the closest analog for what we're *not* building.
- **Diary 083** — `docs/corpus-format.md`, the format spec both
  cookbook and Python reader depend on.
- **`scripts/smoke-test-external-client.py`** — the older precedent
  for Python consuming our wire format via stdio. The battle-log
  reader consumes the same format from disk instead of stdin.
