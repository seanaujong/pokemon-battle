# Diary 032: Move Changes Across Gens — Testing the Registry Hypothesis

**Date:** 2026-04-13
**Status:** Analysis / worked examples

## Hypothesis

If we separate cleanly into **(1) data layer**, **(2) shared `MoveEffect` catalog**, and
**(3) gen-specific `MoveBehavior` registry** (per diary 029), then every move change
across generations can be expressed as a diff in one of those three places — never as
"if gen == X" branches in the engine.

This diary audits real move changes across Gens 1-9, categorizes them by axis, and
tests whether each fits the layered model. If it does, we have a scalable way to
support multi-gen move data without polluting the engine.

## Taxonomy of move changes

Move changes across gens fall into six axes:

1. **Statistical** — power, accuracy, PP
2. **Categorical** — type, physical/special category, priority, target
3. **Existential** — the move exists or doesn't in a given gen
4. **Secondary-effect** — chance, kind, or target of the move's side effects
5. **Behavioral** — the move's core mechanic
6. **Conditional** — the move's behavior depends on state (holder item, type match, etc.)

The claim: **axes 1-4 are data. Axis 5-6 are registry. Neither requires engine branches.**

## Worked examples

### Knock Off — most-cited gen-change move

| Gen | Power | Accuracy | Behavior |
|-----|-------|----------|----------|
| 3 | 20 | 100 | Removes target's held item for the rest of the turn |
| 4-5 | 20 | 100 | Removes target's held item permanently |
| 6+ | 65 | 100 | 1.5× damage if target has an item; removes item |

**Layer assignment:**
- Gen 3-5 → Gen 6+: statistical change (power 20 → 65). **Data layer.**
- Gen 3 → Gen 4+: behavioral change (turn-only → permanent). **MoveBehavior registry.**
- Gen 5 → Gen 6+: conditional damage change (+1.5× if item). **MoveBehavior registry.**

**Can we model it?** Yes:
```kotlin
// Gen 3 MoveDex data: Knock Off = {power: 20, ...}
// Gen 6 MoveDex data: Knock Off = {power: 65, ...}

// Gen 3 registry: KnockOffGen3Behavior.postDamageEvents() returns [SuppressItemForTurn]
// Gen 4 registry: KnockOffGen4Behavior.postDamageEvents() returns [ItemConsumed]
// Gen 6 registry: KnockOffGen6Behavior has both overrideDamageModifier (1.5x if item)
//                 AND postDamageEvents (remove item)
```

Data changes lived in gen-specific CSVs. Behavior changes lived in gen-specific
registry entries. Engine code — the damage calc, the phase — is identical across gens.

### Defog

| Gen | Behavior |
|-----|----------|
| 4-5 | -1 Evasion on target |
| 6+ | -1 Evasion on target + clears entry hazards on BOTH sides |

**Layer:** Pure behavioral change. `MoveBehavior` differs between Gen 5 and Gen 6+.
Data (status move, ONE_OPPONENT target) stays the same.

**Fits.** ✅

### Toxic accuracy

| Gen | Accuracy |
|-----|----------|
| 1-5 | 85% |
| 6-7 | 90% |
| 8+ | 90%, but 100% if user is Poison-type |

**Layer:**
- Gen 5 → Gen 6: statistical (accuracy 85 → 90). **Data.**
- Gen 7 → Gen 8: conditional (accuracy depends on user's type). **MoveBehavior.**
  The `MoveBehavior.effectiveAccuracy(user, move)` hook reads `user.effectiveTypes`.

**Fits.** ✅

### Rapid Spin

| Gen | Power | Extra effect |
|-----|-------|--------------|
| 3-7 | 20 | Clears hazards from user's side |
| 8+ | 50 | + Raises user's Speed by 1 stage |

**Layer:**
- Power: statistical. **Data.**
- Hazard clear: behavioral. **MoveBehavior.**
- Speed boost addition: behavioral OR additional `MoveEffect.StatBoost(SPEED, 1)`
  in the move's effects list. Actually this fits the existing `MoveEffect`, so
  **Data.** (The move's effect list differs per gen.)

**Fits.** ✅

### Bite / Karate Chop / Gust / Sand Attack (Gen 1 retypes)

| Move | Gen 1 type | Gen 2+ type |
|------|-----------|-------------|
| Bite | Normal | Dark |
| Karate Chop | Normal | Fighting |
| Gust | Normal | Flying |
| Sand Attack | Normal | Ground |

**Layer:** Categorical change (type). **Data.** Different type field in each gen's CSV.

**Fits.** ✅

### Return / Frustration

| Gen | Behavior |
|-----|----------|
| 2-7 | Power = floor(friendship × 2/5), capped at 102. Frustration = inverse |
| 8+ | Removed from learnset |

**Layer:**
- Existential: move doesn't exist in Gen 8+ data. **Data.**
- Formula: power depends on Pokemon state (friendship), so damage calc needs dynamic
  power. **MoveBehavior.overridePower(user) → Int.**

**Fits.** ✅ — with the caveat that we'd need to add `friendship` to `PokemonState`,
or treat it as metadata. Another "extend, don't fork" case (diary 028).

### Hidden Power

| Gen | Behavior |
|-----|----------|
| 2-5 | Type and power derived from IVs (16 possible types, variable power ~30-70) |
| 6 | Type from IVs; power fixed at 60 |
| 7+ | Removed from learnset |

**Layer:**
- Existence: gen-data. Gen 7+ MoveDex doesn't include it.
- Power formula: Gen 2-5 uses variable power from IVs. Gen 6 fixes at 60. Both are
  **MoveBehavior** — the hook reads `user.pokemon.ivs` and returns a type + power.

**Fits.** ✅ — MoveBehavior needs an `overrideType(user)` hook in addition to
`overridePower(user)`. Two hooks, composable.

### Scald (didn't exist before Gen 5)

| Gen | Behavior |
|-----|----------|
| 1-4 | — (doesn't exist) |
| 5+ | 80 power Water special, 30% burn chance |

**Layer:** Existential. Not in Gen 1-4 MoveDex data. In Gen 5+ MoveDex data with a
`MoveEffect.ChanceOf(StatusApply(BURN), 30)` secondary effect.

**Fits.** ✅ — assuming we have a `ChanceOf` effect wrapper. (We don't yet; adding
it is a one-time generic addition to the shared effect catalog.)

### Explosion

| Gen | Behavior |
|-----|----------|
| 1-4 | User faints; **halves target's Defense for damage calc** |
| 5+ | User faints; no Defense halving |

**Layer:** Behavioral. The Defense-halving rule was a quirky Gen 1-4 implementation
detail. **Gen-specific MoveBehavior** that overrides damage computation.

**Fits.** ✅ — demonstrates that the same move name can have meaningfully different
formulas across gens, and the registry handles it without damage-calc branches.

### Stomp + Minimize interaction

| Gen | Behavior |
|-----|----------|
| 1-5 | Stomp: 65 power Normal |
| 6+ | 2× damage vs Minimized opponents; never misses them |

**Layer:** Conditional damage modifier. **MoveBehavior.** Reads
`defender.volatiles` for the Minimize volatile.

**Fits.** ✅

### Earthquake + Dig interaction

No gen change — but worth noting: Earthquake hits Digging opponents for 2× damage.
That's a *conditional* damage modifier based on the target's volatile state. Pure
registry hook, not a data change.

### Z-Moves (Gen 7 only)

Each damaging move has a Z-equivalent with different base power and effects.
Z-moves exist only in Gen 7 (and maybe some crossover).

**Layer:** Existential — entirely Gen 7 data. The Z-move catalog is a data table
mapping `(baseMoveType, crystalHeld) → ZMoveData`. Engine uses it via the gimmick phase.

**Fits.** ✅ — it's a lookup table, not a behavior branch.

### Weather-dependent moves (Moonlight, Synthesis, Morning Sun)

| Weather | Heal amount |
|---------|-------------|
| None | 1/2 max HP |
| Sun | 2/3 max HP |
| Rain, Sand, Hail | 1/4 max HP |

**Layer:** Behavioral, state-dependent. **MoveBehavior** that reads `state.field.weather`.

**Fits.** ✅

### Weather Ball

Weather Ball is Normal-type 50 power in clear weather; changes type and doubles power
in weather (Fire in Sun, Water in Rain, Rock in Sand, Ice in Hail).

**Layer:** Extreme state-dependency. **MoveBehavior** overrides type AND power based on
`state.field.weather`.

**Fits.** ✅ — demonstrates that the registry's `overrideType` + `overridePower` hooks
both reading state are sufficient.

### Moves that attack a different stat than normal

**Psyshock / Psystrike / Secret Sword** — special damage but calculated against the
target's *physical* Defense. Introduced in Gen 5.

**Layer:** Behavioral. **MoveBehavior** that tells the damage calc to use a different
defensive stat.

**Fits.** ✅ — requires the damage calc to accept a "defender stat override" from the
move's behavior. Extra hook worth adding.

### Body Press (Gen 8)

Physical move whose damage uses the user's **Defense** stat in place of Attack.

**Layer:** Same shape as Psyshock in reverse — attacker stat override.

**Fits.** ✅

## Findings

### What works seamlessly

- **Statistical changes** (power, accuracy, PP): data CSV.
- **Categorical changes** (type, category, target): data CSV.
- **Existence changes** (not in gen): data CSV (or just absent).
- **Secondary effect additions**: `MoveEffect` list in data (assuming the effect kind
  is already in the shared catalog).
- **Core behavior changes** (Defog adds hazard-clear, Knock Off gets 1.5× item-holders):
  `MoveBehavior` registry with gen-specific implementations.
- **State-dependent moves** (Weather Ball, Moonlight, Stomp-vs-Minimize, Psyshock's
  defender-stat override): `MoveBehavior` hooks that read battle state.

**Every real example I tested fits the layered model.** No "if gen == X" branches
needed anywhere in the engine code. Gen-specific logic lives exclusively in data CSVs
and registry registrations.

### What the registry needs

To support all the examples above, `MoveBehavior` needs these hooks (beyond what diary
029 already sketched):

| Hook | Purpose | Example |
|------|---------|---------|
| `overridePower(user, state)` | Dynamic power | Return, Hidden Power, Weather Ball |
| `overrideType(user, state)` | Dynamic type | Hidden Power, Weather Ball |
| `effectiveAccuracy(user, target, state)` | Dynamic accuracy | Toxic from Poison users |
| `overrideAttackerStat(user, move)` | Use DEF as ATK | Body Press |
| `overrideDefenderStat(user, defender, move)` | Use DEF instead of SPD | Psyshock |
| `damageMultiplierVsTarget(user, target, move, state)` | Conditional multiplier | Knock Off +50% if item, Stomp +100% vs Minimize |
| `postMoveEvents(user, targets, damageEvents, state)` | Effect after damage | Knock Off's item removal, Defog's hazard clear, Rapid Spin's speed boost, Explosion's faint |
| `healFromDamage(user, damageDealt)` | HP drain | Giga Drain, Leech Life |
| `preconditionFails(user, state)` | Fails if condition | Focus Punch (fails if hit this turn) |
| `multiHitCount(user, state)` | Hit count | Rock Blast (2-5), Triple Kick (3 escalating) |

None of these are gen-specific — they're *capabilities* the registry needs. The
gen-specific part is *which move registers which hooks with which logic*.

### Data-layer requirements

For the clean gen-variance story to hold, the data layer needs:

1. **Per-gen MoveDex**. Either separate CSV files per gen, or one file with a
   `(name, gen) → MoveData` lookup. I'd go with separate CSVs for clarity:
   `species-gen3.csv`, `moves-gen3.csv`, etc. A gen-config object says which files
   to load.
2. **Per-gen MoveBehavior registry**. Each gen has its own `MoveBehaviorRegistry` that
   may return different behaviors for the same move name across gens.
3. **Shared `MoveEffect` catalog**. Gen-agnostic list of effect types
   (StatBoost, SetVolatile, StatusInflict, HealFromDamage, etc.). This grows slowly
   and applies to all gens.
4. **Loader translates gen-specific data into uniform engine shape** (per diary 028).

### The meta-result

**The registry hypothesis holds.** Every move change I could think of — across 9 gens
of Pokemon — fits into either the data layer, the shared effect catalog, or the
gen-specific behavior registry. None of them require engine branches.

This validates the architectural arc of diaries 026-029:
- Diary 026 (item registry): shows the pattern
- Diary 027 (ability registry): replicates the pattern
- Diary 028 (data-shape divergence): isolates the *uncommon* case where the pattern
  doesn't apply
- Diary 029 (move-behavior registry): plans the pattern for moves
- **This diary (032)**: demonstrates empirically that the pattern covers the full
  observed design space for move changes across 9 generations

## Concrete next step: per-gen MoveDex loaders

To prove this works in practice, the next data-layer refactor is per-gen move data
files and a gen-aware MoveDex. Sketch:

```kotlin
class GenMoveDex(
    private val dataFile: String,              // "moves-gen5.csv"
    val behaviors: MoveBehaviorRegistry,        // gen-specific behavior registrations
)

object GenVMoves : GenMoveDex("moves-gen5.csv", GenVMoveBehaviors)
object GenIVMoves : GenMoveDex("moves-gen4.csv", GenIVMoveBehaviors)
// ...

// Pipeline picks the right one:
val pipeline = TurnPipeline(
    // ...
    moveDex = GenVMoves,
)
```

This is Diary 033+ work. Not urgent — single-gen support with `MoveDex` singleton is
fine for now. But the architectural seam is where it needs to be.

## Edge cases worth flagging

### Multi-gen behavior that converges mid-gen

Some moves have behavior changes *within* a gen (via patches). Gen 7 had balance
patches. Gen 9 DLC changed some moves. These are effectively "Gen 7a" and "Gen 7b"
sub-versions. If we ever cared, we'd have sub-gen registries — same pattern.

### Sound / Punch / Pulse / Contact move flags

Many moves have flags like "is a sound move" (immune-to-Soundproof), "is a punching
move" (boosted by Iron Fist), "is a pulse move" (boosted by Mega Launcher), "makes
contact" (triggers Rough Skin, Iron Barbs, Flame Body).

These are **data** — boolean flags on Move. Shared across gens. Don't need the
registry.

### Learnset changes across gens

A move may exist in Gen 5 but be unlearnable by Pikachu in Gen 5, then learnable in
Gen 6. That's a **team-construction** concern, not an engine concern. The engine
accepts whatever moves it's given; the legality check lives at team-validation time.

## Summary

- Move changes across 9 gens decompose into 6 axes; all 6 fit the layered model
- Data layer handles: power, accuracy, PP, type, category, priority, target, existence,
  learnset-inclusion, secondary effects composed from shared `MoveEffect` types
- Shared `MoveEffect` catalog handles: common effect patterns (StatBoost, Volatile set,
  Status inflict, ChanceOf wrapper, etc.)
- Gen-specific `MoveBehavior` registry handles: custom formulas, state-dependent
  overrides, unique timing, conditional modifiers, post-move side effects not expressible
  as generic `MoveEffect`s
- No engine branches required anywhere. The engine reads `Move` data and consults the
  registry; it doesn't know or care what gen it's in

The user's instinct was correct: **once data loading is properly separated, the move
behavior registry does carry the full weight of cross-gen move variance.**

## Related diaries

- **Diary 028** — Data-shape divergence principles (what can/can't be projected)
- **Diary 029** — Move-behavior registry taxonomy (15 families of custom moves)
- **Diary 030** — 12 architectural twists (where moves fit in the bigger picture)
- **This diary (032)** — Empirical test of the move-registry hypothesis across 9 gens
