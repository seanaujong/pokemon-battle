# 099 — Gen 5 OU moves expansion (MoveDex shard)

**Status:** Complete

## Goal

Add the 11 (+1 — Psychic, for Latios) Gen 5 OU damage moves the matrix
runner needs so it can load Smogon OU sets without "Unknown move"
lookups. This is the MoveDex shard of a paired worktree run; a sibling
worktree owns the species / ability / item side.

## Scope

Add to `data/src/main/kotlin/com/pokemon/battle/data/MoveDex.kt`:

- Power Whip (Grass, Physical, 120, contact) — accuracy 85 skipped
- Hydro Pump (Water, Special, 110) — accuracy 80 skipped
- Surf (Water, Special, 90)
- Dragon Pulse (Dragon, Special, 85)
- Draco Meteor (Dragon, Special, 130) — accuracy 90 + self -2 SpA skipped
- Psychic (Psychic, Special, 90) — 10% -1 SpD skipped
- Crunch (Dark, Physical, 80, contact) — 20% -1 Def skipped
- Stone Edge (Rock, Physical, 100) — accuracy 80 + high crit skipped
- Dragon Claw (Dragon, Physical, 80, contact)
- Bullet Punch (Steel, Physical, 40, priority +1, contact)
- X-Scissor (Bug, Physical, 80, contact)
- Iron Head (Steel, Physical, 80, contact) — 30% flinch skipped

## Skip list rationale

- **Accuracy** — `Move` has no `accuracy` field. Added inline comments
  where mainline accuracy < 100; no engine change required for this shard.
- **Secondary chance stat-drops** (Psychic SpD, Crunch Def, Iron Head
  flinch) — `MoveEffect` has `StatBoost` / `SetVolatile` but no
  probability-gated variant. Adding one belongs in an effects diary, not
  a dex-expansion diary.
- **Self stat-drops on damaging moves** (Draco Meteor -2 SpA) — same
  constraint as above. `UserStatBoost` exists but is unconditional; a
  damaging move that always self-drops is a common enough Gen 5 shape to
  wire up, but it's not in scope for this worktree.
- **High crit ratio** (Stone Edge) — no crit-ratio system yet.

All skips are documented inline at the move so a future reader can find
them without grepping the diary tree.

## Validation

- [x] `./gradlew :data:compileKotlin` passes.
- [x] `./gradlew test ktlintCheck detekt` green.
- [x] `contact = true` only set for contact moves (Stone Edge corrected
  mid-session — it's a projectile in mainline, not contact).

## Code review

Diagnostic walk:

- **Testable in isolation?** Yes — `MoveDex["Power Whip"]` etc. round-trip
  through the existing engine without any test changes.
- **Readable?** Each move is a single `register(Move(...))` line
  matching existing FLAMETHROWER/SLUDGE_BOMB convention. Skip
  comments are one line each.
- **Layer?** Pure data in the `data` module, no engine touches — correct
  placement.
- **Colocation?** All 12 moves sit under a single `Gen 5 OU additions`
  banner comment; skip reasons live at the moves, not in a separate file.
- **Hard to reverse?** No — deleting the banner block reverts cleanly.
- **Auditable?** Skipped secondaries are grep-findable via `Skipped:`
  (detekt forbids `TODO`/`FIXME` in comments — learned this mid-run, so
  the convention is `Skipped:` followed by the diary reference).
- **Happy path?** Matrix runner loads OU sets; move lookup succeeds;
  damage calc runs.
- **Failure modes?** A skipped secondary silently underpowers or
  overpowers the move relative to mainline (e.g. Draco Meteor without
  self -2 SpA is strictly stronger than cartridge). This is a known
  correctness gap for eval fidelity, not a bug in this change.
- **Duplicated logic?** None — pure declarative additions.
- **Illegal state?** Moves with `contact = true` on non-contact types
  would be a mismatch; cross-checked Stone Edge (non-contact) against
  mainline before shipping.
- **Assumptions enforced?** `Move` validates nothing about contact
  vs. category; we rely on data accuracy. Acceptable for a dex.
- **Mutation?** No — `register` appends to an internal map at class init,
  same pattern as every other move.
- **Names match domain?** Constants are SCREAMING_SNAKE_CASE of the
  display name, matching every existing entry. `X_SCISSOR` is the one
  mild jargon — underscore replaces hyphen, consistent with `U_TURN`.
- **Fits layers?** Yes — `data` depends on `engine` model types only.
- **Easy to remove?** Delete the banner block.

Industry comparison: trivial. This is a data-file append, comparable to
adding rows to a JSON sprite sheet or a Showdown `moves.ts` entry.

No findings requiring follow-up.

## Look ahead

The skip list is the signal for the next round of engine work:
probability-gated secondaries (`ChanceEffect` wrapper over
`MoveEffect`?), accuracy field, self stat-drop on damage, crit ratio.
When matrix-eval fidelity on Gen 5 OU matchups starts to diverge from
Smogon's reference results, that divergence will point at which skip to
land first.
