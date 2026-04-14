# Diary 079: Constructed forcing functions — matrix eval shipped

**Date:** 2026-04-14
**Status:** Complete. Matrix-eval runner exercises `BattleMetadata.playerTags`
and `BattleCorpus.matchupWinRates`, both added in this diary.

## Why this diary exists

Diary 078 shipped the analytics pipeline v1 with several deferrals: player
identity, format registry, per-turn streaming, external sinks, `:server`
recording, incremental aggregations. Each ended with "waits for a real
consumer." Reading the diary back alongside 067 (seams we lack), 070
(multiplexing), 072 (move registry), 073 (KMP audit), the pattern was
obvious: **a stack of plans, all waiting**.

The user's framing: *"don't passively wait for a forcing function,
actually go for it and exercise our architecture."* That observation
turned into a CLAUDE.md workflow update (Preflight #6) and this diary.

The target we chose: **matrix evaluation of AI strategies**. A real
question we can answer — "is TypeAI actually better than RandomAI, or
is it a side-1 matchup artifact?" — that forces `playerTags` on
metadata and a new corpus aggregation. One session, one slice of the
backlog, one piece of signal.

## What was constructed

### New: `BattleMetadata.playerTags: Map<String, String>`

Keyed by `Side.name` convention; value is a caller-supplied tag (AI
strategy name, user handle, anything). Empty map by default so the
engine and non-evaluation callers don't carry bookkeeping they don't
need. `BattleMetadataFactory.forNewBattle` accepts it as an optional
parameter.

### New: `BattleCorpus.matchupWinRates`

Groups persisted battles by `(side1Tag, side2Tag)` pairs and returns a
`MatchupResult` per pair (battles / side-1 wins / side-2 wins / draws).
Deliberately orientation-sensitive: `(TypeAI vs RandomAI)` is a
different key from `(RandomAI vs TypeAI)` because many AI matchups
aren't symmetric (move order, first-turn weather access, etc.).
Collapsing would hide that.

### New: `:cli:matrixEval` runner

Runs every registered-strategy pair for N battles, persists each with
`playerTags` set, then prints the matrix. Usage:

```bash
./gradlew :cli:matrixEval --args="20"
```

## The signal

Running the 2×2 matrix (TypeAI, RandomAI) × (TypeAI, RandomAI), 10
battles each = 40 total:

```
                  vs TypeAI    vs RandomAI
  TypeAI       100% (10/10)   100% (10/10)
  RandomAI        0% (0/10)     80% (8/10)

TypeAI overall vs RandomAI: 100.0% across 20 battles (orientation-averaged)
```

Three findings:

1. **TypeAI vs RandomAI is 100% regardless of orientation.** The prior
   batch's 100% wasn't a lucky matchup — TypeAI genuinely dominates
   RandomAI in this team composition.
2. **TypeAI vs TypeAI is 100% side 1.** TypeAI is deterministic with
   no tie-breaking randomization; whoever moves first (Charizard at
   Speed 100 beats Venusaur at 80) wins the mirror every time. That's
   a real signal — if we cared about TypeAI improvements, self-play
   at these teams would report zero variance. Would need tie-breaker
   randomization or Elo-style rating against multiple strategies.
3. **RandomAI vs RandomAI is 80% side 1.** Structural side-1 advantage
   even with random choices: Charizard's Speed + Thunderbolt
   effectiveness vs Togekiss / Blastoise means a random-move side 1
   often KOs before side 2 can respond. Useful baseline number when
   interpreting any strategy's win rate: anything under ~80% vs
   RandomAI as side 1 is worse than random.

Each of those would have been a guess without the matrix. Now they're
numbers we can track if AI behavior changes.

## Layering compliance (diary 078's commitment, re-verified)

- ✅ `:engine` gained zero new deps. `BattleMetadata.playerTags` lives
  in `:persistence`.
- ✅ `BattleResult` unchanged.
- ✅ `:analytics` gained `matchupWinRates` without I/O — still pure
  sequence folds.
- ✅ `EngineImmutabilityInvariantTest` stays green. The private
  `MutableMatchupCounts` accumulator class inside
  `matchupWinRates` has `var` fields but lives in `:analytics` (which
  isn't scope of the invariant test) and is function-local — classic
  "accumulate then return" shape the invariant explicitly permits.
- ✅ `:server`, `:render`, `:ai` untouched.

## The doctrine (now in CLAUDE.md Preflight #6)

**Forcing functions can be constructed, not just awaited.** The
passive posture (don't speculate; wait for pressure) is correct for
guarding against over-building. The *active* posture (pick a deferred
seam, construct a use case that exercises it) is correct for
validating architecture at rest.

Three signals that the active posture is the right next move:

1. **A backlog of "waiting for forcing function" diaries piles up.**
   When you have 4+ planning-only diaries, the architecture's claim
   that "it's ready for X when X arrives" is untested.
2. **The user can name a real question the system could answer.** The
   matrix question — "is TypeAI actually better?" — was sitting right
   there after diary 078 shipped. The scope of the forcing function
   should be small enough that one session validates it end-to-end.
3. **The forcing function would add real capability, not just
   scaffolding.** `playerTags` is useful beyond the matrix runner.
   `matchupWinRates` is useful beyond this one eval. A forcing
   function that only exercises the architecture without producing
   downstream value is scaffolding work in disguise.

When those signals align, construct. When they don't, keep waiting.

## What didn't get built in this session

- **`:server` recording integration.** The diary 078 item. Would be
  the next constructed forcing function — "save every live battle,
  query aggregates later" — but this session's slice stopped at the
  matrix. Separate diary when forced.
- **Richer AI strategies.** A third strategy (say, `HeuristicAI` that
  respects type immunities + switches out weakened Pokemon) would
  make the matrix 3×3 and produce more nuanced signal. Not needed
  for the first validation.
- **Format registry.** `formatTag` is still a free-form string.
  Elevating it to a typed / structured format would force the
  team-validator seam (diary 067). Out of scope here.

## Related

- **Diary 042** — original analytics framing. The pipeline that
  finally got built 36 diaries later.
- **Diary 067** — seams catalog. This diary implicitly shipped the
  "aliases already did, Dex-contract split partially did, now
  player-tags-as-metadata" slice of the catalog without formalizing
  it as a row; `:persistence` is increasingly the catalog of
  consumer-level seams.
- **Diary 078** — pipeline v1. The architecture this diary exercised.
- **`CLAUDE.md` Preflight #6** — the doctrine this diary codified.
