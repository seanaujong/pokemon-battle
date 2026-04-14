# Diary 073: Non-JVM target — a layering audit for Kotlin/Multiplatform

**Date:** 2026-04-14
**Status:** Planning / reference — deferred until a real non-JVM consumer
appears. Captured now so future-us knows the shape.

## Why this diary exists

The user asked: *"say I wanted to make a fullfledged fan game. How would
that game interact with this engine? Consider gamer ergonomics so they
don't have to spin up a server themselves or be online."*

The honest answer has two halves:

1. **JVM-native game** (Compose Desktop, LibGDX, Korge) — the engine is
   already ready. `jpackage` produces a `.app`/`.exe`/`.AppImage` with a
   bundled JRE; gamer double-clicks to play. No `:server` needed — the
   game links `:engine` + `:data` + `:render` + `:ai` as libraries.
   Zero architectural work.

2. **Non-JVM runtime** (Unity C#, Godot, Unreal C++, web JS) — can't
   link a JVM library. Options:
   - Port the engine. Expensive, drifts immediately.
   - Embed it as a subprocess with IPC (our current `:server` shape).
     Works but has the "spin up a server" UX gamers hate.
   - **Kotlin/Multiplatform.** `:engine` and parts of `:data` could
     compile to JVM, JS, Native. Unity/Unreal consume via Native
     (static library binding); web via JS. The game's UI code is
     whatever the target host language wants; only the engine is
     shared.

This diary is the audit for option 3. We're **not shipping it** — no
consumer demands it. The diary exists so future-us recognizes the shape
when the forcing function arrives, and doesn't accidentally introduce
JVM-isms that make the port harder than it needs to be.

## The expected blockers (not yet verified)

Best-guess audit of what breaks under a KMP compile:

1. **`Pokedex.loadFromClasspath()`** — uses `Class.getResourceAsStream`.
   Native and JS don't have classpath resources. Fix: `expect class
   SpeciesLoader { fun loadAll(): Map<String, Species> }` with
   per-platform `actual`s (resource-classpath on JVM, `fetch` / embedded
   string on JS, bundled file on Native).
2. **`Pokedex.loadFromJsonDirectory(Path)`** — `java.nio.file.Path`.
   Native and JS don't have `java.nio`. Fix: okio's multiplatform Path
   (`okio.Path`), or accept `String` and let callers do their own I/O.
3. **kotlinx-serialization** — already KMP-ready. ✓
4. **Random** — `(1..100).random()` uses `Random.Default`, which is
   KMP (backed by `ThreadLocalRandom` on JVM, different sources on
   other platforms). ✓
5. **Engine logic itself** — pure `val` computation, no `java.*`,
   nothing platform-coupled after 071. ✓
6. **`:data` concrete effects** — same. ✓
7. **`:render`** — no I/O, string-building. ✓
8. **`:ai`** — same. ✓
9. **The ingested JSON species files** — currently live under
   `data/src/main/resources/pokedex/species/`. Native doesn't have
   a resources dir. Fix: codegen them back into Kotlin source (which
   ironically reverses diary 064/069's deletion of the generated
   catalog path). Or embed as a single big string and parse at boot.

Diary 066 already noted the `Dex` lookup-contract split as a deferred
smell; a KMP refactor would force that split because the interface
needs to be in `:engine` (common code) while the implementation is
per-platform.

## The surface KMP would need

```
:engine-common    — common source set, pure Kotlin, no platform imports
:data-common      — common source set
:engine-jvm       — JVM-specific (current `Pokedex.loadFromClasspath`)
:engine-native    — Native-specific (bundled-file loader)
:engine-js        — JS-specific (fetch/inlined loader)
```

Gradle multiplatform plugin, `expect class` / `actual class` for the
platform boundaries. Not structurally hard; the question is *how much
drift* between platforms will accumulate.

## Alternative that may be cheaper

**GraalVM native-image.** The engine runs on JVM; GraalVM compiles the
JVM bytecode to a native binary. No KMP refactor. Smaller binaries
(~20-30MB vs JVM+engine ~80MB), fast startup, no JRE bundling.

Limitation: no reflection without config, no dynamic classloading. Our
engine uses neither, so it's probably a viable escape hatch for
command-line use. Not a path for web embedding, but a path for "ship a
single binary" without committing to KMP.

## What to do today

- **Nothing.** Same conclusion as diaries 070 / 072 / 067's remaining
  rows. No forcing function.
- **Except: keep reading.** When the next enum is added, when the next
  registry grows, when the next loader is written — ask "does this need
  `java.*`?" If yes and it's avoidable, prefer the KMP-clean version.
  The audit above is the checklist.

## The "gamer ergonomics" sub-question

Separate from KMP, the fan-game target has packaging concerns:

- **JVM route:** `jpackage` ships a `.app`/`.exe`/`.AppImage`. Already
  possible today — no architectural work. One Gradle task.
- **Single binary (GraalVM native-image):** above. Good for CLI games,
  less mature for desktop UI.
- **Web:** KMP-to-JS + a thin JS UI. Requires the KMP refactor. No
  server hosting cost if served as a static bundle.
- **Unity/Godot/Unreal:** either KMP-Native (engine as static lib) or
  port the engine to C#/GDScript/C++. KMP is cheaper long-term.

None of these require changing `:engine` today. They change `:data`'s
loader and `:server`'s role.

## Forcing functions that would trigger this diary

- A contributor who wants to build a web-based fan game and hits the
  classpath-loader wall.
- A Unity/Godot prototype that wants to embed the engine.
- A desire to ship a free mobile app (iOS = no JVM at all; Android could
  use JVM but users prefer native binaries).

Any of these and we re-open this diary and execute.

## Related

- **Diary 066** — "`Dex` lookup contract in `sim/` separate from data
  in `data/`" was deferred there; this diary is where that calibration
  actually bites.
- **Diary 069** — the server route, which is the alternative for
  non-JVM consumers when in-process is off the table.
- **Diary 070** — engine concurrency-safety. Most of that audit
  translates to KMP — no shared mutable state, no `java.util.concurrent`.
- **Diary 067** — the forcing-function catalog. This diary is a
  sibling row to its "frontend and server as separate repositories"
  entry: that row is about the *wire* boundary; this diary is about
  the *runtime* boundary. Different question, same "wait for forcing
  function" posture.
