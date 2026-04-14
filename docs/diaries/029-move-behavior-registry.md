# Diary 029: Move-Behavior Registry — Taxonomy and When to Build

**Date:** 2026-04-13
**Status:** Analysis / planning — no implementation yet

## Context

Items got a registry (diary 026). Abilities are next (diary 027). Should moves get one
too? The answer is "eventually, but not yet" — and this diary captures the taxonomy of
move behaviors that justifies it, along with the threshold for when to pull the trigger.

## Why moves are a softer call than items/abilities

Moves already have *some* registry-like structure:
- `MoveDex` is a catalog of `Move` data (name, type, category, power, priority, target,
  effects)
- `MoveEffect` is a sealed interface covering generic per-target effects (`StatBoost`,
  `SetVolatile`, `SelfSwitch`)

For many moves, that's enough. Flamethrower is just data: Fire, Special, 90 power. Swords
Dance is data + a `StatBoost` effect. U-turn is data + `SelfSwitch` effect. The move
executes through the generic pipeline; no per-move code required.

**The registry question is about moves that *don't* fit the generic shape** — moves with
custom execution logic that can't be expressed as data or as a reusable `MoveEffect`.

## Taxonomy of moves requiring custom behavior

Surveying the Pokemon move catalog, custom-behavior moves cluster into patterns:

### 1. Protection family

**Shared mechanic:** self-target, sets a volatile that blocks incoming moves, consecutive-
use success penalty. Different members add their own on-trigger side effects.

| Move | Extra on trigger |
|------|------------------|
| Protect | — |
| Detect | — |
| Spiky Shield | Chip damage to contact attackers |
| King's Shield | Lower attacker's Atk |
| Baneful Bunker | Poison contact attackers |
| Obstruct | Lower attacker's Def by 2 |
| Silk Trap | Lower attacker's Speed |
| Quick Guard / Wide Guard | Block priority / spread moves team-wide |
| Mat Block (Gen 6) | Block all damaging moves team-wide, one turn |
| Endure | Same counter, but survives hits at 1 HP instead of blocking |

**Our status:** Protect is implemented with a bespoke branch in `MoveExecutionPhase`
(`isProtectionMove`, `resolveProtectionMove`, `applyProtectGate`). Scales poorly as we
add Spiky Shield, King's Shield.

### 2. Self-switch family

**Shared mechanic:** damage + switch out after. Different members tweak what else happens.

| Move | Variant |
|------|---------|
| U-turn | Baseline |
| Volt Switch | Baseline, Electric |
| Flip Turn (Gen 8) | Baseline, Water |
| Parting Shot | Lower opponent Atk/SpAtk before switch |
| Baton Pass | **Preserves stat stages and some volatiles** on the replacement |
| Teleport (Gen 8+) | Status move version, slow |
| Chilly Reception (Gen 9) | Sets Snow weather on switch |

**Our status:** U-turn and Volt Switch use generic `MoveEffect.SelfSwitch`. Works. The
moment we add Parting Shot (effect) or Baton Pass (very different preservation rules),
this stops being expressible as pure data.

### 3. Counter-damage family

**Shared mechanic:** damage based on what was received this turn, not the user's stats.

| Move | Formula |
|------|---------|
| Counter | 2× physical damage received |
| Mirror Coat | 2× special damage received |
| Metal Burst | 1.5× any damage received |
| Bide | Accumulates 2 turns, releases double |
| Comeuppance (Gen 9) | 1.5× last damage received |

Requires per-target "damage received this turn" tracking (a volatile).

### 4. Damage-depends-on-action family

| Move | Condition |
|------|-----------|
| Sucker Punch | Fails unless target chose a damaging move |
| Focus Punch | Fails if user was hit this turn |
| Shell Trap | Fires at end of turn if user was physically hit |
| Pursuit | Double damage and **resolves during opponent's switch phase** |
| Revenge | Double damage if user was hit this turn |

Needs timing hooks outside the normal move execution window (Pursuit especially).

### 5. Fixed-damage family

| Move | Damage |
|------|--------|
| Seismic Toss | = user level |
| Night Shade | = user level |
| Dragon Rage | 40 flat |
| Sonic Boom | 20 flat |
| Super Fang | halves target HP |
| Endeavor | matches user HP |
| Psywave | variable 0.5–1.5× user level |

Bypasses the standard damage formula. Could be a `MoveEffect.FixedDamage` or similar,
but each has a slightly different formula — might warrant per-move objects.

### 6. Multi-hit family

| Move | Hits |
|------|------|
| Double Slap, Fury Swipes, Bullet Seed, Rock Blast, Icicle Spear, Pin Missile | 2–5 random |
| Double Kick, Bonemerang, Dual Chop | exactly 2 |
| Triple Kick, Triple Axel | 3, each stronger than the last |
| Water Shuriken | 2–5 (3 for Ash-Greninja) |
| Population Bomb (Gen 9) | 1–10 |

Could be a `MoveEffect.MultiHit(range: IntRange)` but Triple Kick's escalating power
complicates it.

### 7. Move-calling family

| Move | Source of called move |
|------|----------------------|
| Metronome | Random from entire move pool |
| Sleep Talk | Random from user's own moveset (while asleep) |
| Me First | Target's intended move this turn |
| Mirror Move | Last move targeted at user |
| Copycat | Last move used in the battle |
| Assist | Random from allies' movesets (doubles) |
| Nature Power | Move chosen by current terrain |

Each re-enters move execution with a different move. Requires a "replay with different
move" hook.

### 8. Field-state-setting family

Rain Dance, Sunny Day, Sandstorm, Hail / Snowscape, Trick Room, Wonder Room, Magic Room,
Electric / Psychic / Grassy / Misty Terrain.

**These fit cleanly as `MoveEffect` subtypes** (`SetWeather`, `SetTerrain`, `SetRoom`).
Generic, not per-move registry material.

### 9. Delayed-effect family

| Move | Delay |
|------|-------|
| Future Sight / Doom Desire | Fires 2 turns later against the defender's slot |
| Wish | Heals whoever is in the slot 1 turn later |
| Healing Wish / Lunar Dance | User faints; next switch-in full heals |
| Perish Song | All Pokemon faint 3 turns later |

Needs scheduled events — a new field-level mechanism.

### 10. Restriction family

Taunt, Encore, Disable, Torment, Heal Block, Embargo (disables items), Imprison.

Each applies a volatile to the target that restricts their future choices. Cross-cutting
with the choice system — validation belongs at the choice layer, effect at apply time.

### 11. OHKO family

Fissure, Horn Drill, Guillotine, Sheer Cold.

Unique accuracy formula `accuracy = userLevel - targetLevel + 30` (capped), fails if
user level < target level, instant KO on hit. Enough unique logic to warrant per-move
or a small OHKO family handler.

### 12. Accumulating-state family

Fury Cutter, Rollout, Ice Ball, Echoed Voice, Ice Spinner.

Power scales with consecutive-use counter. State carried across turns via volatile.

### 13. Swap-item / state-clone family

Trick, Switcheroo (swap items), Transform / Imposter (copy opponent's entire block),
Recycle (restore consumed item), Fling (throw item as damage), Gastro Acid (disable
ability).

These directly manipulate state we usually consider immutable-in-session.

### 14. Retaliation family

Destiny Bond (if user faints this turn, attacker faints too), Grudge (same, but
deletes the fatal move's PP — PP not modeled in our engine), Spite (reduces PP).

### 15. User-faints family

Explosion, Self-Destruct (Gen 5+ damages through Damp-check), Final Gambit, Healing
Wish, Lunar Dance, Memento.

User survives move execution but faints as a consequence. Already supported via events
but currently no such moves registered.

## The three patterns that DON'T fit `MoveEffect`

Squinting at the above, custom moves fall into three bucket shapes:

### Shape A: Timing hooks outside normal move execution
- Pursuit fires during opponent's switch
- Future Sight fires n turns later
- Fake Out only works turn 1 after switch-in
- Focus Punch fails if hit first

### Shape B: Reads dynamic state the data model doesn't expose
- Counter / Mirror Coat: incoming damage this turn
- Sucker Punch: target's chosen action
- Revenge / Focus Punch: whether user was hit this turn
- Endeavor: user's current HP
- Copycat: last-used move in the battle

### Shape C: Alters the flow of move execution itself
- Metronome / Sleep Talk / Mirror Move: resolve a *different* move
- Multi-hit: repeat the damage loop n times
- OHKO: replace the damage calc entirely
- Trick / Switcheroo: swap non-HP state

`MoveEffect` is data applied per-target after damage. None of shapes A/B/C fit that.

## When to build the move-behavior registry

**Now (today):** zero of our registered moves need it. Protect has bespoke logic in
`MoveExecutionPhase` but it's contained. U-turn uses generic `MoveEffect.SelfSwitch`.

**Threshold: 3+ moves from shapes A/B/C.** Once we have three custom-behavior moves,
the duplicated "dispatch on move identity" logic will start to spread across
`MoveExecutionPhase`, and the registry becomes the natural collapse.

**Concrete trigger:** the first time we add Counter (shape B), Pursuit (shape A), or
Fake Out (shape A). Diary 024's speed control covers Fake Out specifically — so the move
registry likely lands as part of Diary 024 work, or as a diary 030 refactor.

## What the move registry would look like

Sketch (not final):

```kotlin
interface MoveBehavior {
    val move: Move

    /** Replace or veto the default damage calc (OHKO, fixed damage, Counter). */
    fun overrideDamage(ctx: DamageContext): DamageOverride? = null

    /** Check conditions before execution (Focus Punch-fail-if-hit, Sucker-Punch-check). */
    fun preconditionFails(user: PokemonState, state: BattleState): FailReason? = null

    /** Multi-hit count (1 for single-hit). */
    fun hitCount(user: PokemonState): Int = 1

    /** Redirect to another move (Metronome, Sleep Talk). */
    fun substituteMove(user: PokemonState, state: BattleState): Move? = null

    /** Schedule a delayed effect (Future Sight, Wish). */
    fun schedule(user: PokemonState, state: BattleState): ScheduledEffect? = null
}

object MoveBehaviorRegistry {
    fun behaviorFor(move: Move): MoveBehavior?
}
```

Generic moves have no registry entry. Custom moves have one file each.

## Why we're documenting this now instead of building it

Four reasons:
1. **The pattern is becoming obvious** — spelling out the taxonomy makes the eventual
   build straightforward.
2. **The ceiling matters** — knowing the move catalog stresses up to Metronome / Baton
   Pass / Pursuit shapes our `MoveBehavior` interface now, so we don't design a
   too-narrow hook set.
3. **Planning diaries are cheap** — writing the plan costs an afternoon; implementing
   all of it costs weeks. Sketching prevents bad early-stage decisions that lock us in.
4. **The user's instinct was right** — colocation (diary 026) *is* the pattern. Every
   "catalog of named things with per-thing behavior" gets it. Making that explicit as a
   principle is worth the meta-work.

## Related diaries

- **Diary 026** — Item registry (the pattern being applied)
- **Diary 027** — Ability registry (second application)
- **Diary 028** — Data-shape divergence (what registries can't fix)
- **Diary 030 (future)** — Actual move-behavior registry implementation, when 3+
  shape-A/B/C moves are queued
