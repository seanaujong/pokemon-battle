# Diary 030: Architectural Twists Across All Gens + Pokemon Champions

**Date:** 2026-04-13
**Status:** Analysis / planning — no implementation

## Context

Diaries 026-029 extracted a registry pattern for items, abilities, and (eventually)
move-behavior, plus a principle for handling data-shape divergence. That covers
**catalog-growth** and **value-divergence** problems.

This diary zooms out: what if we scan every mainline gen plus Pokemon Champions (TPC's
new dedicated competitive battler, released April 2026) for mechanics that stress the
*core shape* of a battle engine — not "another ability," but "this breaks an assumption
the engine was built on"?

Research conducted via web search; sources cited at the end.

## What Pokemon Champions reveals

Champions is TPC's own answer to the multi-gen engine problem. What they shipped:

### Omni Ring — unified gimmick slot

Instead of Mega Stone + Z-Ring + Dynamax Band + Tera Orb as four parallel once-per-battle
gimmicks, Champions collapses them into **one device** and **one gimmick-used slot per
battle**. The trainer picks Mega today, Tera tomorrow — but only one gimmick fires per
battle total.

**Architectural validation:** a *single* `GimmickState` field on each `Side`, modeled as
a sealed variant (`NotUsed | Used(kind: Mega | Z | Dynamax | Tera)`) — not four parallel
booleans. This is the same pattern as one `item: Item?` instead of `hasLeftovers: Boolean,
hasLifeOrb: Boolean, ...`.

### Regulation Sets as first-class rulesets

Champions launched with "Regulation Set M-A" (Megas on, no Tera, no Restricteds). The
ruleset is a data object, not hardcoded branches. Future sets swap what's legal.

**Architectural validation:** a `Ruleset` object that gates legal choices and tweaks
calc, consumed by the engine but authored externally. Matches our goal of pluggable
gen-specific registries.

### Purely-competitive boundary

No XP, no leveling, no story. The engine consumes an externally-authored `Pokemon`
(IVs, EVs, moves, Tera type via Pokemon HOME) and doesn't own breeding/training.

**Architectural validation:** the engine-vs-application boundary we've been maintaining
is the right one — the competitive kernel is separable from the trainer-progression
layer.

## The 12 architectural twists (ranked by disruption)

From the research, mechanics that would force a *new seam* in a naive slot-based singles
engine, not just new data in existing seams:

### 1. Legends Arceus action-speed scheduler (TOP disruptor)

No turns. Each actor has an action-speed timer; fast Pokemon can act twice before slow
ones act once. Agile/Strong style modifiers per move shift *when* the next action fires.

**Engine impact:** Replace `TurnPipeline` with a **priority-queue scheduler**. "Turn" becomes
a reporting construct. Our phase-list architecture assumes "all sides act per turn" —
LA violates that.

**Scope for us:** Out of reach without a core rewrite. Flag as "not supported," note the
`Pipeline` abstraction would need to be generalized.

### 2. Variable side count / dynamic slot count

- **Battle Royal** (Gen 7): 4 independent sides, win = most KOs when someone empties
- **Horde Battles** (Gen 6): 1v5 asymmetric
- **SOS Battles** (Gen 7): wild Pokemon calls allies, the slot count *increases mid-battle*
- **Max Raids** (Gen 8): 4 trainers vs 1 boss with shared HP bar, simultaneous turn input

**Engine impact:** Our `BattleState` assumes two sides. Real fix: `sides: List<Side>` with
per-side active-slot count and a pluggable `WinCondition`. SOS specifically requires
slot-count to change across turns.

**Scope for us:** Doable. `Slot(side: Side, position: Int)` already supports variable
position per side. The two-sided assumption lives mostly in `BattleState.singles(p1, p2)`
constructors and `opponentSlots(slot)`. Generalizing is ~day of refactoring.

### 3. Commander (Gen 9 doubles)

Dondozo + Tatsugiri on field: Tatsugiri is *swallowed*, Dondozo gets +2 all stats and
becomes untargetable/unswitchable. **Two Pokemon occupy one action slot.**

**Engine impact:** Breaks `slot → Pokemon` bijection. Slots need to hold a stack or a
host-with-passenger model. Targeting logic must exclude the "inside" Pokemon.

**Scope for us:** Niche but instructive. The cleanest model: `Slot.occupants: List<PokemonState>`
with one active and others inactive. Existing code mostly reads the active one; passenger
state becomes a bench variant.

### 4. Reactive hooks on choice/intent (not just resolution)

Examples: **Pursuit** fires during opponent's switch action before switch resolves;
**Magic Bounce** intercepts status moves and reflects them; **Dancer** copies a dance move
after it resolves; **Stakeout** boosts damage *because the target switched in this turn*.

**Engine impact:** The ability/item hook surface needs hooks on **intent** (choice revealed,
move about to resolve) as well as **resolution** (damage dealt, faint, end of turn). Our
current `ItemEffect.afterUserMoveDamage` is a resolution hook; we'd need
`onOpponentChoseSwitch`, `onOpponentChoseMove`, `onMoveReflected`, etc.

**Scope for us:** Medium effort. Means expanding the effect interfaces with more hooks
and reworking the pipeline to fire them at the right points. Justify this when we first
implement Pursuit, Magic Bounce, or Dancer.

### 5. Delayed/scheduled events

Future Sight, Doom Desire — damage fires 2 turns later against the slot (not the
original target Pokemon, who may have switched). Wish heals whoever's in the slot in 1
turn. Perish Song — all on-field Pokemon faint in 3 turns. Healing Wish / Lunar Dance
— one-shot on-switch-in deferred healing.

**Engine impact:** `FieldState` needs a **scheduled-event queue** keyed by turn number,
surviving switches and faints of the original user. Must distinguish "fires in slot X"
vs "fires for user" (Wish is slot-based, Healing Wish is recipient-based).

**Scope for us:** Doable. Add `FieldState.scheduledEvents: List<ScheduledEvent>` with a
turn counter. `EndOfTurnPhase` checks and fires those due this turn. Net-new phase or
addition to existing.

### 6. Hostage-carry / two-actor volatiles

**Sky Drop** takes the target airborne for 2 turns — BOTH actors are in a weird state.
**Shadow Force** has the user semi-invulnerable. If either is ejected (Whirlwind, Red
Card, Emergency Exit), both must be released coherently.

**Engine impact:** Volatile state on one Pokemon can reference another Pokemon. Forced
switches need to consult all volatiles and potentially trigger cleanup on partners.

**Scope for us:** Niche. Add `Volatile.SkyDropUser(target: Slot)` and
`Volatile.SkyDropTarget(captor: Slot)`; forced-switch logic checks both.

### 7. Gimmicks: raw history in engine, budget policy in Ruleset

**Mega** changes stats/ability/type. **Z-Moves** replace the used move and use the slot
for the battle. **Dynamax** doubles HP, replaces movepool with Max moves, ends after 3
turns. **Tera** overrides type; Stellar adds per-type-use counters.

**The budget rules vary by format:**

| Ruleset | Gimmick budget |
|---------|----------------|
| Pokemon Champions / modern VGC | One gimmick (of any kind) per side per battle |
| Smogon National Dex | One Mega AND one Z AND one Dynamax per side per battle |
| Gen 9 VGC (Reg H / Reg I) | Tera only, once per side per battle |

So "single unified slot" is a *Pokemon Champions policy*, not an architectural truth.
Smogon National Dex explicitly allows multiple gimmick kinds simultaneously.

**Engine impact:**
- `Side.gimmicksUsed: List<UsedGimmick>` — raw history only
- `Ruleset.canUseGimmick(kind, priorUsage): Boolean` — ruleset answers legality
- Each gimmick is consumed by a **pre-move phase** that mutates the Pokemon's effective
  state for the duration
- Dynamax especially: max HP temporarily doubles (store the base, derive the double)
- Movepool derivation: Z-Move computed from held Z-crystal + base move; Max moves
  computed from base move type

**Principle:** the engine holds raw state, the ruleset holds policy. Don't bake budget
rules into state shape — a different format will always come along with different rules.

**Scope for us:** Approachable as a future phase. Diary 025's weather-boost pattern
shows the shape: new state field on the side, consulted in calc.

### 8. Mid-battle identity derivation

Mega, Tera, Dynamax, and form changes (Zacian mid-battle Crowned form, Aegislash
Blade/Shield, Palafin Zero→Hero on *next* switch-out-and-back-in) all mean:

**Everything about a Pokemon's effective state must be derived from its base data + its
battle volatiles, never cached at switch-in.**

We already do this for types (`effectiveTypes = typeOverride ?: species.types`). We'd need
to extend to ability (`effectiveAbility`), base stats (`effectiveBaseStats` for Mega), max
HP (`effectiveMaxHp` for Dynamax), moveset (`effectiveMoveset` for Z/Dynamax), and so on.

**Scope for us:** Bookkeeping work. Every time we add a form-change mechanic, another
`effective*` getter shows up. Palafin's deferred trigger is extra spicy.

### 9. FieldState as declarative query layer

Aurora Veil requires Hail/Snow. Grassy Terrain halves Earthquake damage. Electric Terrain
suppresses Sleep. Utility Umbrella ignores Rain/Sun. Steely Spirit boosts ally's Steel
moves. Friend Guard reduces ally damage.

**Engine impact:** Rather than branching on individual conditions, the field exposes a
query-like API: `state.isGrounded(slot)`, `state.damageModifiers(move, attacker, defender)`,
`state.canSleep(slot)`. Modifiers come from layered sources (weather, terrain, screens,
auras, abilities, items) and combine.

**Scope for us:** Emerges naturally as we add more field-interacting mechanics. Worth
refactoring once we have 3+ overlapping queries (weather immunity + terrain immunity +
item immunity for the same attribute).

### 10. Ruleset as first-class gating object

Pokemon Champions exposed what Showdown has always had: the "format" itself is data.
Singles vs doubles, level cap, banlists, type restrictions (Sky Battle, Monotype), move
restrictions (Inverse), win conditions, gimmick availability — all of these are
per-format toggles.

**Engine impact:** A `Ruleset` consumed by the engine:
- Gates legal choices (is Mega Evolution a valid flag on this move?)
- Tweaks calc (Inverse: invert type chart; Monotype: no effect on calc but on legality)
- Controls format shape (singles/doubles/triples/BR)
- Specifies win conditions

**Scope for us:** Big payoff, medium effort. Most of our pipeline is already format-agnostic
thanks to the slot-based design. A `Ruleset` object would consolidate the scattered
"assume singles" spots.

### 11. Per-flag semi-persistent state

Tera Stellar consumes its bonus once per type per battle (18 counters per Pokemon). Mega,
Z, Dynamax are once-per-side-per-battle. Some berries trigger once. Certain moves have
per-turn first-use flags (Fake Out).

**Engine impact:** `SideState.flags: Map<Key, Value>` or similar flexible storage. Hardcoding
booleans breaks at N flags.

**Scope for us:** Low priority until we have 3+ such flags. Diary 022's Choice-lock
volatile is already one; Fake-Out-first-turn will be another; Mega-used will be a third.

### 12. Rotation battles, position-shifting triples

Rotation (Gen 5): three active per side, but only the "center" acts each turn. Players
can **rotate without advancing the turn** — a non-turn action. Triples: moves like Heat
Wave only hit adjacent slots; positions shift as fainted Pokemon are replaced.

**Engine impact:** Non-turn actions and spatial adjacency. Minor but real.

**Scope for us:** Low priority. Add if we ever support Gen 5 triple/rotation formats.

## The 6 meta-lessons (from the research)

Synthesizing the research report, there are six design moves worth baking into our
architecture even before implementing the specific mechanics that demand them:

1. **Pluggable `Ruleset`** that gates choice legality, owns cross-cutting policy
   (gimmick budgets, banlists, win conditions, format-specific calc tweaks), and tweaks
   the type chart (Inverse, Monotype)
2. **Raw history in engine, policy in Ruleset.** Gimmick usage is stored as
   `List<UsedGimmick>` per side; the `Ruleset` decides legality (one-per-battle for
   Champions, one-per-kind for National Dex, Tera-only for Gen 9 VGC). Don't bake
   format-specific budget rules into state shape
3. **`sides: List<Side>`** with variable active-slot counts and a pluggable `WinCondition`,
   not hardcoded 2-side assumptions
4. **Stats/types/ability/moveset always derived**, never stored post-switch-in
5. **Event bus with hooks on intent AND resolution**, so Pursuit, Magic Bounce, Dancer,
   Stakeout can fire at the right semantic moment
6. **Scheduler abstraction general enough** to swap the turn pipeline for an action-speed
   queue (Legends Arceus) without rewriting phases — but note that LA-scale divergence is
   probably better served by a sibling engine (see diary 031)

## What we should actually do about this in our scope

Our project is a tutorial-turned-usable-sim. We don't need to support every gen and
format. But we *do* want the seams to allow growth without rewriting.

**High-value, low-cost upgrades to do before more features:**
- (2) `GimmickState` shape — see addendum below on *policy vs state*
- (4) Expand `effective*` derivations — we have `effectiveTypes`; add `effectiveAbility`,
  `effectiveBaseStats` as getters on `PokemonState` now, even if they just return defaults
- (10) `Ruleset` stub — even an empty `Ruleset` object passed through the pipeline
  establishes the seam

**High-value, medium-cost that should happen when triggered:**
- (3) `sides: List<Side>` refactor — triggered by Battle Royal or Raid interest
- (4) Reactive hooks — triggered by first Pursuit/Magic-Bounce/Dancer
- (5) Scheduled event queue — triggered by first Future Sight / Wish / Perish Song
- (7) Gimmick phase — triggered by first Mega/Z/Dynamax/Tera
- (9) FieldState query layer — emerges from 3+ overlapping queries

**Out of scope (for now):**
- (1) Action-speed scheduler (Legends Arceus) — full rewrite, not justified unless we
  specifically want to support PLA
- (3.Commander) Host-with-passenger slot model — niche, revisit if Gen 9 VGC format
  becomes the target
- (12) Rotation battles — niche

## The lesson behind the lesson

What the research report crystallized: **every gen's big mechanical addition is, at core,
a test of one architectural seam.** Gen 4's phys/spec split tests "how do we categorize
moves." Gen 6's Mega tests "can a Pokemon change mid-battle." Gen 7's Battle Royal tests
"how many sides." Gen 8's Dynamax tests "can max HP change." Gen 9's Commander tests "one
slot, one Pokemon."

If we build with the meta-lessons in mind, adding any *specific* gen mechanic becomes a
registry entry or a new phase — not a structural refactor. That's the architectural
payoff of this whole registry arc.

And Pokemon Champions is TPC validating the approach at their own scale: one device
(Omni Ring), pluggable rulesets (Regulation Sets), gen-agnostic core, data boundary at
Pokemon HOME. The architecture they shipped looks remarkably like the one our diaries
keep arriving at.

## Related diaries

- **Diary 026** — Item registry (first application of the pattern)
- **Diary 027** — Ability registry (second application)
- **Diary 028** — Data-shape divergence (what registries can't fix)
- **Diary 029** — Move-behavior registry (next pattern application)
- **Diary 031** — Engine scope and when to fork (the Legends Arceus question)
- **This diary (030)** — meta-view: what seams the engine will need as gens keep coming

## Sources

- [Pokémon Champions – Wikipedia](https://en.wikipedia.org/wiki/Pok%C3%A9mon_Champions)
- [Pokémon Champions – Official Site](https://champions.pokemon.com/en-us/)
- [Pokémon Champions Gameplay](https://champions.pokemon.com/en-us/gameplay/)
- [Pokémon Champions Battle System Explained – The Click](https://www.theclick.gg/pokemon-champions-battle-system/)
- [Pokémon Champions Regulations – Victory Road](https://victoryroad.pro/champions-regulations/)
- [Pokémon Champions launches Regulation Set M-A – Bulbagarden](https://bulbagarden.net/threads/pokemon-champions-launches-new-ruleset-for-competitive-vgc-regulation-set-m-a-runs-until-june-17th-2026.310333/)
- [Pokémon Champions – Bulbapedia](https://bulbapedia.bulbagarden.net/wiki/Pok%C3%A9mon_Champions)
