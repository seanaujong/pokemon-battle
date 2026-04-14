# Diary 070: Multiplexing and concurrency — one process, many battles

**Date:** 2026-04-14
**Status:** Planning — audit + design space; no code changes.

## Why this diary exists

Diary 069 shipped a JVM `:server` that runs *one battle per process*.
The user's question afterward: *"Does being able to do multiple battles
per process suggest that we did a good job decoupling?"*

Yes — partly. The *engine* looks well-suited to multi-session use; the
*protocol* isn't. This diary splits those and audits each honestly, so
when a forcing function arrives (web UI with concurrent users, ML
self-play corpus, analytics batch runs) we know exactly what it costs
to unlock.

## Part 1: Is `:engine` actually concurrency-safe?

What we'd want: N instances of `ServerSession` running inside one JVM,
each driving its own battle, with no cross-contamination.

### Mutability audit

Grepped `engine/src/main/` for `mutable`, `var`, `ThreadLocal`,
`synchronized`, `Lazy`, `lateinit`. Findings:

**Every `var` and `mutableListOf` is function-local.** Classic
"accumulate then return" pattern in phases, resolvers, and
`BattleLoop.run`. No class-level mutable fields, no top-level
mutable state. Examples:

- `BattleLoop.run`: `var state = initialState` + `val turnHistory = mutableListOf()` — locals, discarded at return.
- `TurnPipeline.resolve`: `var pipeline = PipelineState(...)` + `val events = mutableListOf()` — same shape.
- Every phase: `val events = mutableListOf<GameEvent>()` + `var currentState = state`, return events after the fold.

That's the event-sourcing shape paying off: immutable `val`-everywhere
at the field level, with mutability confined to accumulators that never
escape.

### Registry audit

`ItemRegistry`, `AbilityRegistry`, `MoveDex` are all `object`s with a
`val effects: Map<...>` initialized once at class load. Read-only after
that. Safe to share across N sessions.

`Pokedex` is an `object` with loader functions returning fresh `Map`s;
no hidden cache.

### Randomness audit

`ChanceCheck` is `fun interface`, injected per `MoveExecutionPhase`. The
default implementation uses `(1..100).random()`, which under the hood
goes through `ThreadLocalRandom` — safe under concurrency, but *not*
deterministic if multiple sessions share the default. Deterministic
replay needs per-session seeds, which today would be per-session
`ChanceCheck` implementations carrying their own `Random(seed)`.

### What might still bite us

- **Kotlin `object` init order with test parallelism.** `ItemRegistry`
  references `AbilityRegistry` (Klutz suppression), so their first-use
  order matters. Single-threaded battles serialize this naturally;
  concurrent sessions starting simultaneously could race on init.
  Probably fine because JVM class-init is thread-safe, but worth a
  concurrent smoke test.
- **`java.util.Random` / default random.** If a phase is ever written
  that instantiates `Random()` (no seed) inside a hot loop, two
  sessions on the same millisecond can get the same seed. Our current
  code uses `(1..100).random()` which is `ThreadLocalRandom`-backed,
  so we're safe — but future contributors writing "`Random()`" by
  reflex is a foot-gun. Worth a detekt rule or a convention note.
- **Serialization `Json` instances in `ServerSession`.** Each session
  makes its own. Cheap but not strictly shared; this is fine.
- **Anything we later add that looks like a cache** (memoized stat
  calculations, reusable DamageResult pools) would need to be
  per-session or thread-safe. No such thing exists today.

### Verdict

The engine is *probably* concurrency-safe. The event-sourcing
discipline pre-paid for it — `val`-everywhere and no class-level state
means no shared mutation to worry about. The "probably" is honest:
we'd want a **concurrent smoke test** (spawn N coroutines, each
running a full battle, assert no interference) before actually shipping
a multiplexed server. That test is straightforward to write.

## Part 2: What multiplexing would cost

The protocol, not the engine, is the real cost of multi-battle-per-
process. `Messages.kt` today assumes one conversation per stream.

### Protocol shape options

**A. Session-id envelope on every message.** Every `ClientMessage` and
`ServerMessage` gains a required `sessionId: String` (or `Long`). One
stdin stream carries multiplexed messages; the server dispatches each
incoming message to the session it names, and tags each outgoing
message with its session's id. Clients read only lines matching their
session.

Pros: one stdio transport, minimum protocol surface.
Cons: every line carries an id the current `:cli` / smoke-test flow
doesn't need. Mixed stdout is a readability/debugging regression.

**B. Per-session streams via a connection-setup message.** Client
sends `hello` over stdin; server replies with a session id + a TCP
port (or unix-domain-socket path) for that session. Client reconnects
on the per-session channel. Keeps single-session clients simple; true
isolation.

Pros: each session has a clean stdio-shaped flow, just not over stdio.
Cons: real transport plumbing, real server lifecycle, real testing
story. Cost rises fast.

**C. One process per session, managed externally.** Current shape.
Multiplex via the OS — a supervisor (web UI backend, script) spawns N
server processes. No protocol change.

Pros: the shape we already have. Trivially isolated.
Cons: process-startup cost (JVM warmup). Memory per session is a
full JVM heap (~100MB baseline). Fine at small scale; wasteful at
100 concurrent battles.

### Concurrency models (inside one JVM)

**Coroutines (kotlinx-coroutines).** Natural fit: battles are
IO-bound (waits on client input). Each session runs in its own
coroutine; a single-threaded dispatcher reads stdin and routes to the
right session's `Channel`. Writes to stdout need a mutex or a
dedicated writer coroutine. Standard pattern. New dep on
`kotlinx-coroutines-core`.

**Threads, one per session.** Simpler mental model, no coroutines dep.
One thread per battle blocks on its own input stream. Stdin
multiplexing back to threads is the ugly part — you need a reader
thread that owns stdin, parses lines, routes to per-session queues.
Same complexity as coroutines without the idiomatic sugar.

**Single-threaded cooperative scheduling.** All sessions on one thread;
each is a state machine that yields on input. No concurrency at all
— just async I/O. Lightest footprint, hardest to write correctly.
Probably the right answer *if* we were throughput-bound, but we're
not; battles wait on client decisions for seconds at a time.

**Recommendation when forced:** option A protocol + coroutines model.
That's what Showdown does (in TypeScript, with async iterators over a
single stream). Matches industry convention for JSONL multiplexing.

### Forcing functions that justify the work

None of these are present today:

- **Web UI with multiple concurrent players.** One server process per
  open browser tab doesn't scale — you want one daemon with many
  sessions.
- **Self-play training corpus.** ML eval wants 1000 battles in
  parallel; spinning 1000 JVMs is absurd. But N coroutines inside one
  JVM with a ChanceCheck seed pool is fine.
- **Long-lived analytics / metrics server.** A persistent process that
  handles submit-a-battle RPCs over the course of hours.

Until one of these is real, option C (one-process-per-session, no
protocol change) is the right level of complexity.

## Part 3: The step that's worth taking *now* (maybe)

Not multiplexing. *Documenting the concurrency audit.* The only part
of this diary that has a shelf life longer than "when the forcing
function arrives" is the fact that the engine passed the audit.
Future contributors who introduce class-level mutable state, a default
`Random()` without a seed, or a cached calculation should know that
the engine is currently safe and that multiplexing is the reason to
keep it that way.

Concrete proposal (optional follow-up):
- Add a *"Concurrency safety"* section to `architecture.md`, pointing
  at this diary.
- Add a detekt rule or a CLAUDE.md convention note: *"When rolling a
  random number, use `(range).random()` (ThreadLocalRandom) or inject
  a seeded `Random` via `ChanceCheck`. Don't construct bare `Random()`
  inside a phase or resolver."*
- Write a `ConcurrentBattleSmokeTest` that launches N=8 coroutines,
  each running a scripted battle to completion, asserts no shared-state
  corruption (e.g., every session's event log matches a reference
  single-threaded run). ~50 lines.

The smoke test is the real deliverable. If it passes today, we have
*proof* of concurrency safety; the rest is upkeep. If it fails,
we've found the bug now rather than at the forcing function.

## What I'm explicitly not proposing

- Actually building a multiplexer. No consumer demands it.
- Adding `kotlinx-coroutines`. No code needs it today.
- Changing the wire protocol. It's young; one `sessionId` field later
  is cheap.
- Splitting `:server` into `:server-daemon` + `:server-cli`. Also
  premature.

The diary's job is to make the *next* decision cheaper, not to
pre-build the next decision's implementation.

## Open question

**Does writing the concurrent smoke test today count as "premature"?**
Argument for: it catches latent concurrency bugs when the engine is
small and the invariants are clear — perfect time to lock them in.
Argument against: diary 067's rule was "extract a seam when pressure
is one diary away, not five" — the forcing function for multiplexing
is arguably more than one diary away.

Lean: write the test. It's tiny, it's self-validating, and it converts
"probably concurrency-safe" into "verified concurrency-safe" for zero
ongoing cost. The diary is then the documentation layer on top.

Would do as a follow-up if there's appetite; otherwise this diary is
the audit on its own.

## Related

- **Diary 054** — event streaming design; one of the same forcing-
  function-driven patterns this diary uses.
- **Diary 065** — `:data` extraction; the discipline that keeps
  engine lean enough for this audit to be tractable.
- **Diary 067** — seams we lack; "battle-stream" row closely related.
  Multiplexing is the natural follow-on to battle-stream.
- **Diary 068** — `internal` visibility audit; the same audit at the
  symbol level as this is at the concurrency level. Both are about
  locking down what's real contract vs what's implementation detail.
- **Diary 069** — the server this diary audits. The single-battle
  shape of that diary is the constraint this one would lift if forced.
