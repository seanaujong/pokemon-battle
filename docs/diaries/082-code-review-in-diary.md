# Diary 082: Code review lives in the diary — and the 078–081 retroactive pass

**Date:** 2026-04-14
**Status:** Process update + retroactive review. CLAUDE.md iteration loop
step 5 updated; the 078–081 wave gets the review it should have had
inline with each diary.

## Why this diary exists

Two observations from the user, arriving in the same message:

1. "Certain code-review steps haven't really been showing up."
2. "Would [industry comparisons] help... surely the comparisons will
   guide us."

The first is a correctness problem. CLAUDE.md's iteration-loop step 5
says write the review to `docs/diaries/temp/` and delete after fixing.
In practice that directory was almost never touched this session — the
review step got silently skipped on most commits, and there's no grep
signal when a temp file doesn't exist. A convention that fails silently
is a convention that will be skipped.

The second is a scope question for step 5 itself. We added a whole
diary (081) for the industry comparison because there was no natural
home for it. Retrofitting that comparison into the review step makes
it recurring — every substantial change pays the cost of naming where
it sits relative to industry.

## The process update

**CLAUDE.md iteration loop step 5 now requires:**

1. A `## Code review` section inside the diary itself (not a temp file).
2. The 16 diagnostic questions walked — note which apply, note "no
   findings" explicitly where true.
3. For substantial changes (new module, new public interface, new data
   format, new workflow), an industry-comparison note: what's the
   closest industry analog, where do we agree / differ / deliberately
   depart.
4. Previous `docs/diaries/temp/` convention is retired.

**Why inline, not a temp file:** temp files that don't exist generate
no signal. In-diary sections are visible to every reader of the diary,
and easy to grep (`grep -L "## Code review" docs/diaries/*.md` lists
diaries that skipped the step).

**Why industry comparisons are part of review, not a separate diary:**
diary 081 has value, but the cost of doing it was "realize we haven't
done it in 40 diaries, write a big comparison document." Spreading
that cost across individual diaries — one paragraph per review —
catches drift earlier and less painfully.

## Retroactive review: diaries 078–081

This is the review the 078–081 wave should have had inline. Catching
it retroactively now so future readers of those diaries see what we'd
have caught at the time.

### Diary 078 (analytics pipeline plan)

Not shipped code — just a plan. Review dimensions that matter for
plan-shaped diaries:

- **Layering discipline section:** present. Explicitly names where
  things don't leak. ✓
- **Forcing-function threshold:** named (three candidate consumers).
  Good.
- **Missing:** industry comparison was absent. Added as diary 081
  after the fact. Should've been a paragraph in 078. Going forward:
  planning diaries that propose new modules get this paragraph.
- **Unclear in retrospect:** the `BattleRecorder` interface location
  oscillated (`:server` in 078's text, `:persistence` in the shipped
  071-style interface placement). 078 could have been more explicit
  that we'd decide at implementation. Harmless — we picked cleanly —
  but worth noting: design diaries should mark "will decide at
  implementation" explicitly, not silently hedge.

### Diary 079 (matrix eval / constructed forcing functions)

Shipped code: `BattleMetadata.playerTags`, `BattleCorpus.matchupWinRates`,
`:cli:matrixEval` runner.

**Diagnostics:**

- *Testable in isolation:* `matchupWinRates` is pure; tested via
  scripted corpus. ✓
- *Readable:* `MatchupResult` data class with named accessors is clear.
  The `MutableMatchupCounts` private class is functional-accumulator-
  shaped; slightly less idiomatic than a `fold`, but the imperative
  version reads 1-line-per-concept. Acceptable. Would rewrite with
  `groupBy` + `eachCount()` if doing it again, but not worth churning
  now.
- *Layer:* `:analytics` gained no I/O. `playerTags` lives in
  `:persistence`. ✓ matches diary 078's commitments.
- *Auditable:* the printed matrix is the audit. Files in `./battles/`
  back it. ✓
- *Happy path:* clear — run battles, record, aggregate, print.
- *Failure modes:* a battle whose metadata lacks `playerTags` is
  bucketed under `("?", "?")` — visible in the matrix, not silently
  dropped. ✓
- *Illegal state:* the matrix runner currently hardcodes "TypeAI" and
  "RandomAI" strings in two places (the `strategies` list and the
  `provider` function). A typo in one would produce "Unknown strategy"
  at runtime instead of compile time. Small improvement possible:
  make strategies a sealed class or enum. Not urgent at 2 strategies;
  revisit if the strategy set grows past 3.
- *Invariants in our heads:* the assumption that `Side.name` is the
  stable key in `playerTags` is implicit. If `Side` ever renames its
  enum values, `matchupWinRates` breaks silently. Worth a test that
  asserts the key convention.

**Industry comparison (should have been in 079 at the time):**
experiment tracking tools (MLflow, W&B) would handle this shape
natively — each matchup run becomes an "experiment" with tags; W&B
would diff across runs over time. We're doing the hand-rolled version
because 2 strategies × 1 engine version = no diffs worth tracking. If
the strategy or engine-version cardinality grows, revisit.

**Findings to fix:** none urgent. The strategies-as-enum and the
Side.name test are worth adding when we next touch this code.

### Diary 080 (server recording)

Shipped code: `ServerSession` gains `recorder` + `metadata`
parameters; `ServerMain` wires them via env var.

**Diagnostics:**

- *Testable:* two new tests — capturing recorder, null-metadata
  skip. ✓
- *Readable:* `recordBattle` helper encapsulates the end-of-session
  write. Clear. ✓
- *Layer:* `:server` now depends on `:persistence`. Acceptable — a
  server consumer wanting persistence is a direct dependency, not a
  leak. Matches how `:cli` depends on `:persistence`.
- *Failure modes:* if recording fails (disk full, permissions),
  `FileBattleRecorder.record` throws `IOException` which is not
  caught in `ServerSession`. The session's try/catch at the top of
  `run()` catches `IllegalStateException` / `IllegalArgumentException`
  but not `IOException`. An I/O failure at end-of-battle would crash
  the server rather than emit an `error` message. **This is a
  finding.** Fix: extend the outer catch in `run()` to also handle
  `java.io.IOException`, emit `ErrorMessage`, close cleanly.
- *Auditable:* yes — every recorded battle has a UUID file.
- *Happy path:* documented in test and in the `ServerMain` docstring.
- *Illegal state:* `recorder != NoOp && metadata == null` is
  allowed and silently skips. Tested (`no metadata means no recording`).
  Maybe this should be a `require(...)` instead — if the caller passed
  a real recorder, null metadata is probably a wiring bug, not
  intentional. Worth considering; not urgent.

**Industry comparison:** Kafka-style event stream infrastructure
would handle the same concern (reliable durable writes). We're at
"one producer, one consumer" scale so filesystem writes are right-
sized. Log-rotation concerns (old battles filling the disk) are an
eventual issue — filesystem-based retention policies or a periodic
GC task.

**Findings to fix:** IOException handling in `ServerSession.run()`.
Adding that in this commit.

### Diary 081 (industry comparison)

Not shipped code — just a comparison. Review:

- *Coverage:* 9 industry shapes mapped. Missed: data catalogs (Amundsen,
  DataHub), feature stores (Feast, Tecton), real-time feature
  computation (Flink). All irrelevant to us today, but worth noting the
  scope is "batch + analytics" not "all of data infra."
- *Actionable findings:* 3 concrete small moves listed. None shipped
  yet — but they're in the diary for pick-up.

**Industry comparison of this diary itself:** recursive. Skipping.

## Fix shipped in this commit

Finding from diary 080 review: `IOException` in `FileBattleRecorder`
would crash the server. Extending the outer catch in `ServerSession.run()`
to handle it, with a test.

## CLAUDE.md update also shipped

Step 5 of the iteration loop now:
- Puts review in the diary, not a temp file
- Mandates the 16 diagnostics explicitly
- Mandates industry comparison for substantial changes
- Retires `docs/diaries/temp/`

## Code review of this diary

Following the rule: meta-review.

- *Testable:* not applicable (a diary).
- *Auditable:* future reviewers can grep diaries for "## Code review"
  sections and find where the process was (or wasn't) followed.
- *Happy path:* clear — every diary gets a review section inline.
- *Failure mode:* a contributor skips the section anyway. Mitigation:
  a test or script that greps diaries for the section. Worth adding
  when/if we notice drift again.
- *Duplicated logic:* the 16-question list is now duplicated in
  CLAUDE.md. Single source of truth (CLAUDE.md) is fine; individual
  diaries can refer rather than re-enumerate.

**Industry comparison:** most mature codebases have PR-template-driven
review (GitHub's `.github/PULL_REQUEST_TEMPLATE.md`, GitLab's
description templates). Our flavor is diary-embedded because we're
diary-first. Functionally equivalent; ours is easier to grep over
time because it's in-tree Markdown, not a PR history requiring API
access.

**Findings to fix:** none. Ship.

## Related

- **Diary 079** — constructed forcing functions doctrine (active vs
  awaited). This diary extends the doctrine to *review rigor* — the
  active move here was noticing the convention was silently failing
  and fixing the convention.
- **Diary 081** — industry comparison that informed "add industry
  comparison to step 5."
- **CLAUDE.md** iteration loop step 5 — now the source of truth for
  the review process.
