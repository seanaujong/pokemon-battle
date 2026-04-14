# Diary 035: Hook Signature Convention — Taming Parameter Creep

**Date:** 2026-04-13
**Status:** Complete

## Context

After completing diary 023 (competitive abilities), a reflection surfaced: the registry
hook interfaces had accumulated 5-6 parameter methods that were *genuinely* needed but
felt unwieldy. The most egregious:

- `onHolderTookDamage(holder, holderSlot, attacker, attackerSlot, state, damageDealt)` — 6 params
- `onHpThresholdCrossed(holder, slot, state, previousHp, currentHp)` — 5 params
- `afterUserMoveDamage(user, userSlot, move, damageLanded)` — 4 params, inconsistent (no state)

A detekt `LongParameterList` warning on `onHolderTookDamage` had been suppressed with a
"each param is a distinct dimension" comment that, on reread, felt defensive.

## Two options on the table

### Option A: Context object

```kotlin
data class OnHitContext(
    val state: BattleState,
    val holderSlot: Slot,
    val attackerSlot: Slot,
    val damageDealt: Int,
)

fun onHolderTookDamage(ctx: OnHitContext): List<BattleEvent> = emptyList()
```

**Pros:** Fewer parameters per hook. Adding a new field doesn't change hook signatures.
Implementations can destructure.

**Cons:** Hides what the hook needs. At the call site, you can't see at a glance which
fields the hook reads. One object for many hooks becomes a god-blob; per-hook objects
add 5+ more data classes.

### Option B: Remove redundant params

Audit the bloated signatures. What's actually necessary?

- `holder` = `state.pokemonFor(holderSlot)` — derivable, **redundant**
- `attacker` = `state.pokemonFor(attackerSlot)` — derivable, **redundant**
- `currentHp` = `state.pokemonFor(slot).currentHp` — derivable, **redundant**
- `state`, `slot`, `move`, `previousHp`, `damageDealt`, `damageLanded` — each irreducible

Drop the derivable. **`onHolderTookDamage` goes from 6 params to 4. `onHpThresholdCrossed`
from 5 to 3. No abstraction; just honesty about what's needed.**

## The decision

Option B. Context objects are right at larger scale (10+ fields per hook, many hooks
sharing them) but here at 4 params max, positional args are clearer — you can read the
signature and see everything the hook needs.

The meta-insight: **Pokemon state that can be derived from a `BattleState` + `Slot`
shouldn't be passed alongside them.** Passing the `PokemonState` was a convenience that
turned into redundancy: the state can become stale mid-hook-chain if earlier events
mutate HP/item/ability, while `state.pokemonFor(slot)` always returns the current snapshot.

## Convention adopted

> **Stateful hooks take `(state: BattleState, slot: Slot, …extra context)`.** The
> holder's `PokemonState` is derived via `state.pokemonFor(slot)`. Pass only
> non-derivable extras.

Exceptions that keep their shape (they don't have `state` in scope by design):

- `attackerDamageModifier(attacker, move)` / `defenderDamageModifier(defender, move)` — called
  from the damage calculator which is state-light on purpose (just attacker/defender/move)
- `interceptIncomingDamage(defender, rawDamage)` — same calc context
- `speedModifier(holder)` — called from `SpeedResolver` which takes only a PokemonState
- `blocksMove(defender, move)` / `blocksWeatherDamage(weather)` — leaf predicates, no state needed

## Final signatures

**ItemEffect:**

| Before | After | Delta |
|--------|-------|-------|
| `afterUserMoveDamage(user, userSlot, move, damageLanded)` | `afterUserMoveDamage(state, userSlot, move, damageLanded)` | 4→4, consistent |
| `endOfTurn(pokemon, slot)` | `endOfTurn(state, slot)` | 2→2, consistent |
| `onHpThresholdCrossed(holder, slot, state, prevHp, currHp)` | `onHpThresholdCrossed(state, slot, prevHp)` | **5→3** |
| `onHolderTookDamage(holder, holderSlot, attacker, attackerSlot, state, damage)` | `onHolderTookDamage(state, holderSlot, attackerSlot, damage)` | **6→4** |

**AbilityEffect:**

| Before | After | Delta |
|--------|-------|-------|
| `onMoveAbsorbed(defender, slot, move)` | `onMoveAbsorbed(state, slot, move)` | 3→3, consistent |
| `onHpThresholdCrossed(holder, slot, state, prevHp, currHp)` | `onHpThresholdCrossed(state, slot, prevHp)` | **5→3** |

The `@Suppress("LongParameterList")` on `onHolderTookDamage` is gone. Its comment went
from "each param is a distinct dimension" to "the signature is 4 params and reads
directly." Progress.

## Impact

- **Files touched:** 2 interfaces + 6 implementations + 3 caller sites (~11 files).
- **Signature breakage:** every override and every call site. But Kotlin's compiler caught
  them all; the refactor was mechanical.
- **Tests:** 189 pass unchanged. Behavior identical.
- **Net lines:** slightly fewer (removed redundant param names, fewer caller arguments).

## Tradeoffs acknowledged

### What we gained
- No more `LongParameterList` suppressions
- Consistent pattern: stateful hooks all read `(state, slot, …)`
- Hook bodies that need the holder do `val holder = state.pokemonFor(slot)` — one line
- The derived `holder` reflects the *current* state mid-chain, eliminating a class of
  stale-data bugs we hadn't hit yet but would have

### What we gave up
- Micro-convenience: implementations that use the holder now need one line to derive it.
  Before: it was already a parameter.
- Call sites with a PokemonState readily in scope now have to pass `state` instead. Same
  length; more honest about what flows.

### What we didn't do
- **Context objects.** Not at this scale. Reconsider if any single hook exceeds 5 params
  or if many hooks share the same 4+ fields.
- **`ctx.holder` shorthand.** A `PokemonContext(state, slot)` mini-class with a
  `holder: PokemonState by lazy { state.pokemonFor(slot) }` property would save the one
  line but add one class. Not worth it yet.

## Principle

**Don't pass what's derivable.** If parameter Y is `f(X)` for a well-known `f`, passing
both is redundancy that becomes stale-data risk when the chain mutates state. Pass X,
derive Y where needed.

## Related

- **Diary 026** — item registry, where the hook interface was introduced
- **Diary 027** — ability registry, where the parallel interface grew
- **Diary 023** — competitive abilities, where the parameter creep became visible
- **CLAUDE.md** — "Is understanding colocated?" — hook signatures should show what they
  need; derivable params hide that
