# Diary 042: Analytics — The Event Log as a Data Asset

**Date:** 2026-04-14
**Status:** Planning — opportunity exploration

## The insight

Our event log isn't just for rendering text. It's a **structured stream of everything
that happened**, emitted by every turn, in data classes that are already immutable and
serializable-friendly. That's the shape of a data pipeline's input.

The renderer is one consumer of this stream. Analytics would be *another consumer,
parallel to the renderer, reading the same events*. The engine wouldn't change; we'd
add new modules that subscribe to what already flows.

This is the second time we've noted "the event log is load-bearing beyond its obvious
use" — the first was in architecture.md Lessons Learned, about auditability. Analytics
is the same property in a different frame.

## Where analytics belongs architecturally

**Not in the engine.** The engine's job is to produce correct events. Aggregation,
metrics, histograms, joins — these are consumer concerns, same as rendering.

**As modules above the engine** (per diary 041's multi-module pattern):

```
pokemon-battle/
├── engine/                — emits BattleEvent stream
├── render/                — currently a package inside engine; could be its own module
├── analytics/             — NEW: consumes events, produces derived metrics
│   ├── BattleAnalyzer.kt  — single-battle summary (winner, turn count, KOs)
│   ├── MoveUsageTracker.kt — counts moves across many battles
│   ├── CriticalHitAuditor.kt — observed crit rate vs expected (engine self-consistency)
│   └── ReplayExporter.kt  — structured JSON output for external tools
├── cli/                   — I/O shell
└── data-ingestion/        — PokeAPI + Smogon (diary 041)
```

Analytics is a **data warehouse consumer**. Engine emits the facts; analytics aggregates
them.

## Three distinct analytics categories

Worth distinguishing because they solve different problems:

### 1. Gameplay analytics (business / player metrics)

"What's the KO rate of Stealth Rock?" "How often does Protect block a move?" "Which
items win more often?" These are **player-facing insights** that help teambuilding,
meta analysis, match review.

**Consumer shape:** batch process over thousands of battle logs. Output: CSV / Parquet /
dashboard. Analogous to what Smogon publishes monthly from Pokemon Showdown battles.

**Connection to diary 041:** once we have ingestion running, we could run simulated
battles between common OU sets and publish *our own* usage stats — a feedback loop
where Smogon stats prioritize what to implement, and analytics from our own battles
validate that we behave realistically.

### 2. Engine self-consistency analytics (correctness)

"Did the observed crit rate match the expected 1/24?" "Did any Move fail for an
unexpected reason?" "Is the damage calc's randomness actually uniform over 85-100?"

**Consumer shape:** fuzz-test harness. Run 10K battles with controlled seeds; collect
histograms; flag deviations. Catches regressions and silent correctness bugs.

**Example:** if we ever refactor `resolveMoveOrder` and accidentally break the
speed-tie coinflip distribution, the analytics would catch it before it lands a PR.

### 3. Engine performance / shape analytics (dev metrics)

"How long does turn resolution take?" "How many hooks fire per damage event?" "Is
`ItemRegistry.effectForHolder` being called in a hot loop that allocates?"

**Consumer shape:** instrumentation + benchmarks. Not events per se — more like
profiling output. But the event count and types per turn are a good proxy for work
done.

## What the event log already has vs what's missing

### Present
- **Every state change** is an event (damage, status, stats, volatiles, weather, switches, faint)
- **Every decision point** is an event (MoveOrderDecided, MoveAttempted, MoveFailed with reason)
- **Event order** is meaningful — the sequence tells the story
- **Every event is serializable** — data classes map cleanly to JSON via kotlinx.serialization

### Missing (arguably)
- **Turn number on each event** — currently we emit events per turn and the BattleState
  has a turn counter, but the events themselves don't carry turn metadata. Reconstructing
  "what turn did this faint happen on" requires counting `EndOfTurnPhase` outputs. Small
  gap; would be one field on `BattleEvent`
- **Causation links** — "this faint was caused by that Life Orb recoil which was caused
  by that move" — currently implicit in ordering. For richer analytics (causal graphs,
  attribution), a per-event `causedBy: UUID?` would help
- **Timing metadata** — not in events; would be a separate instrumentation channel
- **Choice context** — what choices were available that weren't picked? Current log only
  records what *did* happen, not the counterfactual. An AI policy analyzer would want
  "top-3 considered moves" but that's above the engine

**Verdict:** event log is ~90% analytics-ready as-is. The turn-number-on-events gap is
worth closing the next time we touch event shapes. Causal links and counterfactuals are
separate projects.

## What a minimum-viable analytics module looks like

```kotlin
// analytics/src/main/kotlin/BattleAnalyzer.kt

data class BattleSummary(
    val winner: Side?,
    val turnsPlayed: Int,
    val koCount: Map<Slot, Int>,
    val movesUsed: Map<String, Int>,         // move name → count
    val itemsTriggered: Map<Item, Int>,
    val abilitiesTriggered: Map<Ability, Int>,
    val criticalHits: Int,
    val damageDealt: Map<Slot, Int>,
    // ... room to grow
)

object BattleAnalyzer {
    fun analyze(result: BattleResult): BattleSummary = // fold events into summary
}
```

A CSV exporter / dashboard layer sits on top. The engine stays untouched.

## Why this deserves its own diary rather than a feature

Because the framing matters more than the code. The engine is currently designed as
"turn resolution → events → rendering." Elevating events to "turn resolution →
**analytics-ready stream** → multiple consumers" formalizes something that was only
implicit. Future decisions ("should this new mechanic emit an event or just update
state silently?") get a clearer answer: if you want it analyzable later, emit the event.

Specifically this reframes three past decisions:
- **Diary 007 (slot-based architecture)** — events per-slot aren't just for rendering
  multi-target moves; they support per-slot analytics
- **Diary 026 (item registry)** — `ItemConsumed`, `ItemDamage`, `ItemHealing` let us
  count item activations separately
- **Diary 035 (hook signature refactor)** — dropping redundant parameters didn't lose
  data; it's all reconstructible from the event stream + state snapshots

## Plan — not to build now, but to acknowledge

### If we did build it
1. **Add turn number to BattleEvent** (one field, propagate through existing emissions)
2. **Create `analytics/` module** with a dependency on engine events only
3. **Ship `BattleAnalyzer.analyze(result)`** for single-battle summaries
4. **Integrate with diary 041's fuzz harness** — every simulated battle produces a
   `BattleSummary`; aggregate across N=1000 runs into meta-metrics
5. **Consider serializing events to JSON for external tools** (Showdown replay format
   compatibility is a stretch goal)

### Why defer
- The event log isn't going anywhere; we can always add analytics later against
  battles we've already run (assuming we log event JSON)
- Most valuable when combined with diary 041 (ingestion gives us teams; analytics
  gives us insights from simulating them)
- Engine feature work has higher immediate payoff than a consumer that won't be
  used until there's interesting data to consume

### Trigger to build
- Once **diary 041 is live** and we're running simulated battles at scale
- Or when someone asks a question about battle outcomes that can't be answered by
  inspection (e.g., "does Sitrus Berry actually affect win rate?")
- Or as a **correctness audit** after a big refactor (run 10K battles, check the
  distributions haven't shifted)

## The layer question

The user's original framing: "maybe the layer above the engine is what is concerned
here."

**Confirmed: yes.** The engine's correctness story is "emit events faithfully." Analytics
is the layer *above* that interprets those events for humans or for derived metrics. Same
position as rendering, same position as ingestion's dual (ingestion: data → engine;
analytics: engine → insights).

The engine's contribution is keeping events **complete, ordered, and serializable**. We
already do the first two. The third is a `@Serializable` annotation away.

## One concrete addition worth doing even without analytics

Regardless of when we build the analytics module, **add kotlinx-serialization to
`BattleEvent`** now. Annotate all event subclasses. This:

- Makes every event JSON-dumpable for free
- Enables replay storage (save a battle, load it later for analysis)
- Is cheap — ~1 annotation per event class
- Creates the "log everything as JSON" primitive without building consumers

This is the minimum viable investment that unlocks everything downstream. Worth its
own small diary when we decide to do it.

## Related

- **Diary 030** — meta-lesson about auditability: "If this produced unexpected output,
  could you trace back why from the system's own records?" — analytics formalizes that
- **Diary 041** — ingestion + analytics pair naturally; stats flow in, insights flow out
- **Architecture.md — Application Layer** — already lists "Analytics pipeline (not yet
  implemented)" as a future concern. This diary tightens the "what does that look like"
  answer
