# Diary 043: Organizing for Parallel Work — Code, Teams, and the Middle Ground

**Date:** 2026-04-14
**Status:** Planning / principles

## Two distinct axes that often get conflated

When someone asks "is this module organization good for parallelism," two things are
actually in play:

1. **Code organization** — packages, modules, file layout. Affects cognitive load,
   build times, dependency management, merge conflict frequency.
2. **Team organization** — who owns what, who reviews, who's on-call. Affects
   coordination overhead, decision-making speed, knowledge distribution.

They interact (Conway's Law: systems mirror the communication structure of the
organizations that build them). But **they aren't the same thing, and forcing one
from the other is a mistake in both directions**:

- "We have two teams, so the code needs two modules" → can produce artificial splits
  that fight the domain
- "We have two modules, so we need two teams" → can underutilize flexible contributors
  who could work across boundaries

**The honest framing for our project:** we're 1-2 contributors (human + AI pair).
We're not at the scale where team structure is driving anything. The parallelism
question here is really: *could one agent be working on feature X in one module while
another agent works on feature Y in a different module, without stepping on each
other's edits?*

That's code organization, not team organization. The principles below answer that.

## The three scales of collaboration (and what matters at each)

### Scale 1: 1-2 contributors

**What matters:** cognitive isolation. "Can I work on the engine without having to
load the web UI's React code into my brain?"

**What to do:**
- Packages, not modules, for most separation
- One module is often plenty
- Split to modules when one of two pressures hits:
  1. **Dependency isolation** — e.g., keeping React/Node out of Kotlin engine tests
  2. **Build time** — a module takes long enough to compile that not-rebuilding-it
     matters

**What NOT to worry about:**
- CODEOWNERS files
- Module version compatibility
- API contracts between modules
- Team ownership boundaries

**Our current state:** one module. That's right for 1-2 contributors. The multi-module
plan in diary 041 is motivated by *dependency isolation* (React/HTTP deps not leaking
into engine), not by team structure.

### Scale 2: 3-10 contributors

**What matters:** coordination without bureaucracy. Every new contributor increases
the number of people who can conflict over a single file.

**What to do:**
- Modules where dependency isolation OR build time matter
- Clear file/package ownership even within a single module (code reviewers, not
  formal "teams")
- Stable API contracts between modules (versioned if anything is published)
- Shared primitives (events, types) in a core module everything depends on

**What this adds over Scale 1:**
- Explicit module boundaries for frequently-touched areas
- Lightweight ownership (e.g., "Alice is the ability-registry reviewer")
- Documented conventions (new items go in their own file; registry edits are
  mechanical)

### Scale 3: 10+ contributors / multiple teams

**What matters:** Conway's Law starts dominating. Modules become team boundaries
whether you planned it or not. Coordination overhead scales with cross-module
change frequency.

**What to do:**
- Modules that match the way the team actually works
- Strong API contracts between modules (tests, versioning, deprecation cycles)
- Ownership formalized (CODEOWNERS, on-call rotations per module)
- Release cadence per module if possible (team A can ship engine changes without
  waiting for team B's UI changes)

**This is out of scope for us** and will be for a long time. Flagging for completeness.

## Parallelism chokepoints in our current layout (Scale 1, but worth knowing)

Even at 1-2 contributors, some patterns in the code make parallel work harder than
it needs to be. Running through the architectural review with a "two agents editing
simultaneously" filter:

### Chokepoint 1: Registry catalog files

`ItemRegistry`, `AbilityRegistry`, `MoveDex` — each is a single file with a `listOf(…)`
containing every entity. Adding an item requires editing this file.

**Two contributors adding items in parallel:** merge conflict on the `listOf(…)`.
Usually auto-resolvable (git handles parallel list insertions), but the file becomes
a bottleneck psychologically — every new item means "touch a shared file everyone
reads."

### Chokepoint 2: Shared enums

`Item`, `Ability`, `Volatile`, `StatusCondition`, `SideHazard`, `SideCondition` — each
new entry requires editing the enum. Same dynamics as the registry.

### Chokepoint 3: TextRenderer's `when (event)` dispatch

One giant `when` block with a branch per event type. Every new event type adds a line.
Same shared-file bottleneck.

## Escalation path for chokepoints

Each chokepoint has a progression from "accept it" to "eliminate it":

### For registries (chokepoint 1)

| Level | Approach | Cost | Payoff |
|-------|----------|------|--------|
| 0 | Accept the shared file (current) | 0 | List-insertion merges are auto-resolvable |
| 1 | `init { Registry.register(this) }` in each effect | Small | No registry edits, but object must be referenced to load |
| 2 | JVM `ServiceLoader` with `META-INF/services` | Medium | Standard JVM pattern; each file drops in independently |
| 3 | Gradle code generation (scan source, generate Registry.kt) | High | Zero shared-file edits; compile-time |

**Recommended:** stay at level 0 until we cross ~3 contributors or ~50 items.
Level 2 is the natural next step.

### For enums (chokepoint 2)

| Level | Approach | Cost | Payoff |
|-------|----------|------|--------|
| 0 | Accept the shared enum (current) | 0 | Enum edits are trivial |
| 1 | `sealed interface` split across files | Small | Each item is its own file; no shared edit point |
| 2 | String keys + runtime validation | Medium | Fully extensible; loses compile-time exhaustiveness |

**Recommended:** stay at level 0. Sealed-across-files is a real option (Kotlin 1.5+
supports it) but the tradeoff — losing the clean `when (item)` exhaustiveness — isn't
worth it until the enum bottleneck actually bites.

### For TextRenderer dispatch (chokepoint 3)

| Level | Approach | Cost | Payoff |
|-------|----------|------|--------|
| 0 | Accept the `when` (current) | 0 | Works |
| 1 | Per-event-type renderer registry, similar to `ItemTextRegistry` | Small-medium | Each event type's render lives in its own file |

**Recommended:** level 1 when we next add 3+ event types in quick succession.
Diary 038's pattern applies cleanly here.

## What to do now (no, really, at our current scale)

**Nothing structural.** The chokepoints are known, the escalation paths are mapped,
the 1-2 contributor reality means none of this bites today. Merge conflicts on a
registry list are resolved by anyone who looks at git output.

**What has shifted** with this analysis:
- The multi-module split from diary 041 is now motivated by *two* reasons, not one:
  dependency isolation (original) AND cross-module parallel work (this diary)
- We have a conscious escalation path if/when the chokepoints bite
- Future diaries that feel tempted to add *another* shared registry file can check
  themselves against this: "will every new X edit this file?" If yes, think about
  chokepoint level 1+ from the start

## Mentoring notes (the broader "how do I think about this" answer)

A few heuristics the user might find useful:

### "Separation of concerns" happens at multiple granularities

- **Within a file:** functions, classes, single responsibility per class
- **Within a module:** packages, layered architecture (engine / phase / render / data)
- **Across modules:** dependency direction, build isolation, deployable units

Most teams focus on module splits too early. Good intra-module separation (clear
package boundaries) gets you 80% of the benefit of modules without the complexity.
We have this — `engine/`, `phase/`, `render/`, `model/`, `data/`, `gen/simplified/`,
`engine/item/`, `engine/ability/` are all clean package boundaries that could become
module boundaries later without restructuring.

### "Will I edit this file often?" is the bottleneck test

Files that grow linearly with features (registry catalogs, dispatching `when`s,
shared enums) are the natural bottlenecks. You can often tell a design has this
problem by asking: *what's the hottest file in git log?* If one file is edited in
every PR, it's a chokepoint even if nobody's complained yet.

### Parallelism is only one axis

Code that's highly parallelism-friendly can be harder to reason about. A file full
of auto-registered singletons with no central list is great for parallel contributions
but worse for "show me everything this system supports." Centralized registries are
the opposite.

The right answer depends on which costs dominate. At our scale, centralization wins.
At Showdown's scale (thousands of items, many contributors), auto-registration wins.

### Inverse Conway is real

If you want a monolith, keep one team. If you want microservices, have multiple teams
that can't easily edit each other's code. Architecture will drift to match
organization, so choose the organization with the architecture you want in mind.

At our scale, this doesn't matter. At Scale 3, it's the dominant factor.

## Related

- **Diary 041** — multi-module plan; this diary adds parallelism motivation to it
- **Diary 042** — consumer catalog; helps identify which modules can parallelize cleanly
- **Architecture.md** — the module-placement rule (engine has zero I/O) is now
  reinforced by parallelism reasoning
