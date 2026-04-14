# Diary 060: Event + Input serialization DTO split (plan)

**Date:** 2026-04-14
**Status:** Plan — deferred until a forcing function arrives

## The problem

`BattleEvent` is `@Serializable` directly on the domain type (diary 050).
Every subclass (`DamageDealt`, `SwitchIn`, `MoveOrderDecided`, 30+ more)
has the annotation. Field names on these events are the on-disk format.

Diary 055 Phase 2 extends the same pattern: `InputRequest`,
`InputResponse`, `SwitchTargetRequest`, `SwitchTargetResponse` all get
`@Serializable`. Consistency with the existing event hierarchy, same
latent coupling cost.

**The cost when it bites:** rename any field on any event or input type
→ every stored battle log becomes unreadable. A serialized event log from
yesterday is tied to the exact field names in yesterday's code.

We caught this for `Species` (diary 041) before shipping, split it into
`Species` (domain) + `SpeciesJson` (DTO). That split is already paying
off: the PokeAPI ingestion pipeline sits cleanly on the DTO without
dragging storage concerns into the engine's domain.

## Why we're not fixing now

1. **No active consumer.** Diary 050 shipped event serialization because
   tests needed deterministic round-trips. No production replay, no
   saved-battle system, no network protocol — yet.
2. **Piecemeal is worse than uniform.** Splitting just `InputRequest`
   into `InputRequestJson` while events remain naked would make the
   codebase inconsistent — future readers would have to remember which
   types have DTOs and which don't.
3. **The forcing function is specific.** Once we ship a *persisted* or
   *networked* feature that actually reads saved event logs, we'll have
   concrete constraints on what the DTO layer needs (versioning,
   backwards-compat reads, schema evolution rules). Designing the DTO
   now without those constraints risks over- or under-shooting.

## What a future split looks like

One coherent diary, touching every `@Serializable` domain type in the
engine:

- **Event hierarchy.** `BattleEvent` stays domain; introduce
  `BattleEventJson` sealed type with per-variant `toDomain()` and a
  top-level `fromDomain(BattleEvent): BattleEventJson`. Serialization
  annotations live only on the `*Json` types.
- **Input hierarchy.** Same pattern for `InputRequest` / `InputResponse`
  and their concrete variants.
- **Nested value types.** `Move`, `Item`, `Ability` enums appear inside
  events — these need either (a) a wrapper in the DTO, or (b) stay
  serialized by name (loose coupling via `@SerialName`). Prefer (b)
  where practical.
- **Event log reader/writer.** One module-level utility converts
  `List<BattleEvent>` ↔ `List<BattleEventJson>` ↔ `String` (JSON).
- **Migration of callers.** `EventSerializationTest` today exercises
  the naked-domain path; it becomes the DTO round-trip test.

## Triggers to do this

1. **Saved battles feature.** If users want to save/load mid-battle or
   review a completed battle later, stored battles become a long-lived
   artifact — schema stability matters.
2. **Networked multiplayer.** Wire format between clients requires a
   stable schema independent of engine refactoring cadence.
3. **Replay tooling.** If analytics or debugging tools read event logs
   programmatically, the schema becomes an external API.
4. **First time we regret a rename.** A field rename breaks a test's
   serialized fixture. Or someone deletes an event type thinking it's
   unused and stored logs become unreadable.

Until one of these lands, the current naked-`@Serializable` pattern is
fine — it costs nothing in practice. Premature DTO split is the
over-engineering we were warned about.

## What to do in the meantime

When adding new events or input types, **stay consistent** with the
current naked-`@Serializable` pattern. Don't introduce partial DTO
layers (e.g. DTO for one type family but not others) — that makes the
eventual full split harder, not easier.

## Related

- **Diary 041** — `Species`/`SpeciesJson` split. The proof-of-concept.
  Done *before* shipping, because the cost was trivial at that scope.
- **Diary 050** — original event serialization. Chose naked
  `@Serializable`. This diary is the follow-up thinking.
- **Diary 055** — added `@Serializable` to `InputRequest`/`InputResponse`
  during Phase 2. Same pattern, same deferred cost.
