# Diary 094: HeuristicAI learns Tera — closing diary 092's loop

**Date:** 2026-04-14
**Status:** Complete.

## Why this diary exists

Diary 092 shipped Terastallization as an engine mechanic and showed
the damage formula produces the right numbers in isolation. But no
AI used it — so our 3×3 matrix was running Gen 9 rules with every
Pokemon ignoring its signature gimmick. The immediate forcing
question: *"does Tera actually matter on our team pool, or is it
mechanically correct but practically inert?"*

Answer one way or the other, then we know whether to invest further
(Tera Blast, UI prompts, etc.) or leave the mechanic shipped-and-sat.

## What shipped

- **`HeuristicAI.maybeRequestTera`** — a two-line heuristic: if the
  holder's tera type equals the move type about to be used, the
  ruleset permits, and the holder hasn't already tera'd, flip
  `terastallize=true` on the choice. Intentionally primitive — a
  smarter AI is diary-future work; this one just proves the pipe
  works.
- **`MatrixEvalMain` takes a `tera` third arg.** When present, each
  Pokemon in the pool gets a tera type (matching one of its strong
  STAB moves so the heuristic triggers), and the initial state gets
  `ruleset = Gen9VgcTeraRuleset`. Output dir + formatTag become
  `<gen>-tera` so gen+tera runs don't collide with gen-only runs.
- **`Gen9VgcTeraRuleset` made public** (was internal). The ruleset
  choice is a library-consumer concern, same as the registry and
  damage-calc choices; it deserves the same public surface.
- **Tera-type assignments** (for the hardcoded matrix team pool):
  - Charizard → Fire (double-STAB Flamethrower: 2.0×)
  - Garchomp → Ground (double-STAB Earthquake)
  - Lucario → Fighting (double-STAB Aura Sphere)
  - Venusaur → Poison (double-STAB Sludge Bomb)
  - Blastoise → Ice (1.5× STAB on Ice Beam — coverage flip rather
    than double-STAB because Blastoise is Water, not Ice)
  - Togekiss → Fighting (1.5× STAB on Aura Sphere — Togekiss is
    Normal/Flying, so Tera flips Aura Sphere from 1.0× to 1.5×)

## The empirical result

Matrix run, 20 battles per matchup, seed-reproducible (diary 089):

```
Gen V (no Tera):
                  vs TypeAI   vs RandomAI  vs HeuristicAI
  TypeAI       100% (20/20)  100% (20/20)  100% (20/20)
  RandomAI       20% (4/20)   65% (13/20)   30% (6/20)
  HeuristicAI  100% (20/20)  100% (20/20)  100% (20/20)

Gen V + Tera:
                  vs TypeAI   vs RandomAI  vs HeuristicAI
  TypeAI       100% (20/20)  100% (20/20)  100% (20/20)
  RandomAI       20% (4/20)   65% (13/20)   25% (5/20)
  HeuristicAI  100% (20/20)  100% (20/20)  100% (20/20)
```

**Tera fires in 100 / 180 battles** — exactly the 5 matchups where
HeuristicAI appears on either side (3×3 matrix = 9 matchups, 5
involve HeuristicAI, 5×20 = 100). RandomAI and TypeAI never
Terastallize; the count is a clean identity check.

**Observable win-rate delta:**

- RandomAI vs HeuristicAI: **30% → 25%** (HeuristicAI gains 1 battle
  out of 20 as side 2 when it has Tera available).
- All other cells unchanged. The saturated matchups (TypeAI vs
  anything, HeuristicAI vs RandomAI as side 1) stay at 100-0.
- Sanity: RandomAI mirror unchanged at 65% (RandomAI doesn't use
  Tera, so enabling it shouldn't shift this). ✓
- Sanity: TypeAI mirror unchanged at 100% (TypeAI doesn't use Tera
  either). ✓

The headline is honest rather than dramatic: **Tera is mechanically
working, but our team pool + AI heuristics don't see a big payoff.**
Reasons:

1. **HeuristicAI's matchup is already saturated vs TypeAI (100-0).**
   Tera can't improve on a perfect record.
2. **HeuristicAI already beats RandomAI comfortably** even without
   Tera; the extra damage nudges it from 70% to 75%, visible but
   small.
3. **TypeAI still picks the strongest-typed move without Tera-aware
   scoring.** It ignores its own tera type. The heuristic is asymmetric
   on purpose — we wanted to measure HeuristicAI's Tera use alone.

## What this doesn't prove

It doesn't prove Tera is "weak." It proves that on *this* team pool,
with *this* naive heuristic, against *these* opponents, the payoff
is small. Real Gen 9 VGC play derives most of Tera's value from:

- **Defensive tera** (Terastallize into a type that removes a
  weakness you're about to be hit by). Our heuristic is purely
  offensive.
- **Timing** (Tera on the turn your opponent expected to KO you).
  Our heuristic fires on the first turn the move matches, which is
  usually turn 1.
- **Coverage flips** (Tera into a move your base typing wouldn't
  STAB). We do exercise this for Blastoise/Togekiss but it's only
  1.5× gain, not 2.0×.

A smarter Tera heuristic — or an MCTS-style lookahead — would likely
produce a larger delta. Filing as a diary-future candidate.

## Code review

### Diagnostics

- *Testable:* `maybeRequestTera` is a pure function of `(state, slot,
  choice) -> choice`. HeuristicAI's existing test coverage plus the
  matrix-as-test pattern cover the observable behaviour. No new unit
  test written — the integration test is "run the matrix twice, diff
  Tera activation counts" (100/180 vs 0/180). If someone breaks the
  path, the matrix output will show it.
- *Readable:* four early returns followed by a one-line `copy`, same
  shape as `maybeTerastallize` in `MoveExecutionPhase`. Two
  independent gates (holder can Tera, choice should Tera) at two
  layers (engine, AI) that use the same vocabulary.
- *Layer:* the AI decides *whether* to request Tera; the engine
  decides *whether to grant* it. The ruleset authoritatively gates
  the engine side. If the AI asks for Tera when the ruleset forbids,
  the engine silently ignores — the advisory-flag pattern.
- *Auditable:* the recorded battle files contain `Terastallized`
  events at the activation turn. The Python script that counted
  them worked first try.
- *Happy path:* HeuristicAI sees holder with teraType, ruleset is
  Gen9VgcTeraRuleset, chosen move matches tera type → choice gets
  `terastallize=true` → engine grants → `Terastallized` event lands
  in log → Tera STAB rule applies → damage is 1.5×–2.0× higher that
  turn.
- *Failure modes:* no teraType set → fall through (any tera-enabled
  matrix that didn't assign types would just never trigger).
  Ruleset forbids → fall through. Move type ≠ tera type → fall
  through. Already tera'd → fall through. All four are silent, all
  four are fine.
- *Duplicated logic:* the gate shape (`teraType != null`,
  `!terastallized`, `canUseGimmick`, move matches) appears both in
  AI (as "should I request") and in engine (as "can I grant"). Not
  quite duplication — the AI only requests when the move matches,
  the engine doesn't care about move type. Intentional split.
- *Invariants:* the existing "Tera is one-way" invariant continues to
  hold. HeuristicAI can't force Tera to fire twice because
  `holder.terastallized` gates it.
- *Names:* `maybeRequestTera` on the AI side, `maybeTerastallize` on
  the engine side — same "maybe" prefix, different verb. Request vs
  grant. Readable as a pair.
- *Layer-blur:* none. The AI doesn't know the STAB math; the engine
  doesn't know the AI's choice shape beyond the `terastallize` flag.
- *Removal:* delete `maybeRequestTera`, revert the loop to the
  original three-line shape, delete the tera-types map and the
  `tera` arg parsing in `MatrixEvalMain`. ~20 lines across 2 files.
  Zero other code touched.

**No findings. One design tradeoff (naive heuristic) explicitly called
out above as a future-work candidate.**

### Industry comparison

- **Showdown's AI in singles battle** is notoriously aggressive with
  Tera — uses it reflexively on turn 1 for offensive bonuses. Our
  HeuristicAI is directly in that tradition (turn-1 aggressive Tera
  when the STAB line matches). Showdown's top-tier human players
  have since learned to hold Tera for defensive moments; the AI
  mostly hasn't caught up. We're at Showdown-AI level on this axis
  and that's fine — measuring the mechanic, not winning OU.
- **DeepMind's StarCraft II agent (AlphaStar)** learned to time
  one-shot abilities (e.g. Storm) via self-play rather than rules.
  At sufficient scale, "when to Tera" is a policy-learning problem;
  rule-based AI will always leave value on the table. Filing as a
  "consumer for the AI seam" possibility down the line.
- **What we're deliberately not doing:** defensive Tera (inspect
  opponent's likely move, Tera into a resisting type). That needs
  an opponent-prediction subsystem, which we don't have. Would add
  honest signal but also significant AI complexity.

### Findings to fix

None filed. Two "what this doesn't prove" items flagged above —
defensive Tera + smarter heuristic — are both future-diary-candidate,
not findings.

## Validation

- `./gradlew test ktlintCheck detekt` green.
- `./gradlew :cli:matrixEval --args="20 genv"` — produces 180
  battles under `battles/genv/`. 0 Terastallized events (sanity
  check: Tera disabled = no activations).
- `./gradlew :cli:matrixEval --args="20 genv tera"` — produces 180
  battles under `battles/genv-tera/`. 100 Terastallized events
  (exactly the matchups where HeuristicAI plays on either side).
- Python diff on outcome counts confirms the described deltas.

## Related

- **Diary 092** — Tera mechanic ship. This diary is its real-world
  test. Closes the loop 092's "no AI uses it yet" left open.
- **Diary 089** — seeded engine randomness. Makes the 5-point shift
  in RandomAI-vs-HeuristicAI attributable to Tera rather than noise.
- **Diary 088** — contact resolution. Same family of "the measurement
  is only meaningful if other axes are locked down."
- **Diary 078** — matrix runner. The forcing function that all of
  090/092/094 plug into.
- **Future:** smarter AI (MCTS / policy-lookahead) would exercise
  Tera much harder. Filed as the consumer-driven push in the
  post-093 roadmap conversation.
