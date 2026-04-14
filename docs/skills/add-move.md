# Skill: Add a move to the engine

**Scope:** `:data` (MoveDex entry), `:engine` (new `MoveEffect` variant if needed,
dispatch branch in `MoveExecutionPhase`).
**Level:** user goal.
**Primary actor:** contributor adding a new move.

## Stakeholders and interests

- **Contributor** — wants most moves to be a one-line addition to `MoveDex`.
  Only reaches for new engine-side work when the move has a genuinely novel
  secondary effect.
- **Engine maintainers** — want new moves to land as *data* wherever possible.
  The explicit goal (from diary 029) is "behavior-as-data for moves."

## Preconditions

- You know the move's type, category (PHYSICAL / SPECIAL / STATUS), power,
  accuracy, priority, target, and any secondary effect.
- Existing moves in `MoveDex.kt` are the cheapest reference.

## Success guarantees

- The move is listed in `MoveDex` and usable as `MoveDex.YOUR_MOVE` or
  `MoveDex["Your Move"]`.
- If the move has a secondary effect, it resolves correctly through
  `MoveExecutionPhase.resolveEffect`.
- `./gradlew test ktlintCheck detekt` stays green.

## Trigger

You want a move the engine doesn't have yet — or you want to expand a move's
behavior (e.g. add a burn chance to an existing fire move).

## Main success scenario

1. **Try to make it data-only.** Open
   `data/src/main/kotlin/com/pokemon/battle/data/MoveDex.kt` and add your move
   using the existing `register(Move(...))` pattern. Set power, accuracy,
   priority, target, and `effects` as a list of existing `MoveEffect`
   variants (StatBoost, SetVolatile, SelfSwitch, SetTrickRoom,
   SetSideConditionOnUserSide, SetHazardOnOpposingSide,
   ClearHazardsOnUserSide, UserStatBoost).
2. **If the existing `MoveEffect` variants cover your move, you're done** —
   run `./gradlew test`, assert on the move's behavior in an existing or new
   test file, and commit. No engine-side changes.
3. **If the effect genuinely doesn't fit any variant,** see *Extensions*
   before continuing.
4. **Test it** in
   `engine/src/test/kotlin/com/pokemon/battle/<Category>Test.kt`
   (group with similar moves — see `MultiHitMovesTest` for multi-hit,
   `HazardRemovalTest` for hazard manipulation, etc.). Pin `roll = { 100 }`
   and `chanceCheck = { _, _ -> false }` for deterministic assertions.
5. **Validate:** `./gradlew test ktlintCheck detekt`. Commit.

## Extensions

**3a. Your move needs a new `MoveEffect` variant.** Before adding one, check
   diary 029 — the move-behavior registry is planned but deferred. The
   threshold for extracting a `MoveEffect` registry is "3+ shape-A/B/C moves
   queued"; we're currently below it.

   If your move crosses the threshold or is genuinely unique:
   - Add the variant to the sealed `MoveEffect` interface in
     `engine/src/main/kotlin/com/pokemon/battle/model/MoveEffect.kt`.
   - Add a branch to `MoveExecutionPhase.resolveEffect` that translates the
     variant into one or more `BattleEvent`s. The `when` is exhaustive, so
     the compiler enforces the branch.
   - Do *not* scatter move-name checks across phases or calc. The new
     variant is the abstraction; anything downstream should only know that
     variant, not your specific move's identity.

**3b. Your move is shaped more like *data* than *behavior*.** Example: a
   variable-BP move whose power depends on weight, or a stat-dependent
   move. Prefer adding a *field* on `Move` (like `variablePower: (attacker,
   defender) -> Int`) over adding a sealed variant. Diary 047 observed
   parallel agents self-routing around the sealed interface using this
   reasoning — it's a legitimate pattern when the "effect" is really a
   parameter.

**3c. Your move modifies the engine in a way `MoveEffect` can't express**
   (e.g. a move that alters the field for future turns, or changes
   pipeline ordering). File a diary first — that's a phase-level change,
   not a move-level one.

**4a. Your test reveals an ordering bug (e.g. your move fires before/after
   where it should).** The order inside `MoveExecutionPhase` — move
   attempt → damage calc → intercepts → damage event → secondary effects
   → on-hit hooks — is the authority. Adjust the phase, not your move.

## Related information

- **Canonical design:** diary 029 (the still-deferred move registry — read
  this *before* adding a new `MoveEffect` variant; the diary plans the
  extraction and you may be the forcing function).
- **Future evolution:** when the threshold is crossed, the `MoveEffect`
  sealed hierarchy becomes an `IMoveBehavior` interface and per-move
  behavior objects, mirroring items/abilities after diary 071. The skill
  doc will update when that lands.
- **Worked examples in repo:** `MoveDex.FLAMETHROWER` (pure damage),
  `MoveDex.U_TURN` (self-switch), `MoveDex.ROCK_BLAST` (multi-hit),
  `MoveDex.PROTECT` (volatile), `MoveDex.STEALTH_ROCK` (hazard set).
- **Gotcha:** the `contact: Boolean` flag on `Move` doesn't exist yet.
  Items like Red Card and Rocky Helmet that "should only fire on contact"
  currently fire on all damaging moves. If your move's mechanic
  genuinely depends on the contact distinction, that's a `Move`-shape
  change first, not a move-data change.
