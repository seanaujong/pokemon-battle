# Diary 014: Battle Renderer — Event Log to Text

**Date:** 2026-04-13
**Status:** Complete

## Goal

Build a renderer that walks the event log and produces text output matching the games' message style. This validates that the event log is a complete and sufficient source for battle presentation.

## Design

### Core interface

```kotlin
fun interface BattleRenderer {
    fun render(event: BattleEvent, state: BattleState): List<String>
}
```

The renderer receives each event and the *current state after the event was applied*. It returns zero or more text lines. The caller walks the event list, applying each event and passing the post-event state to the renderer.

Why post-event state? The renderer needs to look up Pokemon names from slots, and the state tells it who's in each slot *after* switches, faints, etc.

Actually — for some events the renderer needs the *pre-event* state. "Venusaur took 162 damage!" needs to know Venusaur was in that slot *before* a potential faint replacement. Let me think...

The caller should pass both:

```kotlin
fun interface BattleRenderer {
    fun render(event: BattleEvent, stateBefore: BattleState, stateAfter: BattleState): List<String>
}
```

Most events only need `stateBefore` (to look up the Pokemon's name in the slot the event targets). `SwitchIn` needs `stateAfter` (to know who arrived). Having both available means the renderer never has to guess.

### Text output style

Matching the games:

```
--- Turn 1 ---
Charizard used Flamethrower!
It's super effective!
Venusaur took 133 damage! (155 → 22 HP)
Venusaur used Sludge Bomb!
Charizard took 72 damage! (153 → 81 HP)

--- Turn 2 ---
...
```

HP numbers in parentheses are optional — the games don't show them, but damage simulators do. We'll include them since they're useful for debugging and match what our tests care about.

### What each event renders to

| Event | Text |
|-------|------|
| MoveOrderDecided | Silent (ordering is implicit in who goes first) |
| MoveAttempted | "Charizard used Flamethrower!" |
| MoveFailed(ASLEEP) | "Charizard is fast asleep!" |
| MoveFailed(FROZEN) | "Charizard is frozen solid!" |
| MoveFailed(FULLY_PARALYZED) | "Charizard is fully paralyzed!" |
| DamageDealt | "It's super effective!" / "It's not very effective..." + HP change |
| PokemonFainted | "Venusaur fainted!" |
| StatChanged | "Charizard's Attack rose sharply!" / "fell!" |
| StatusApplied | "Venusaur was burned!" |
| StatusCleared(SLEEP) | "Charizard woke up!" |
| StatusCleared(FREEZE) | "Charizard thawed out!" |
| StatusDamage | "Venusaur is hurt by its burn!" |
| WeatherDamage | "Infernape is buffeted by the sandstorm!" |
| ItemHealing | "Swampert restored HP using its Leftovers!" |
| WeatherTick | "The sandstorm rages." / "The sandstorm subsided." |
| WeatherSet | "It started to rain!" |
| SwitchOut | "Charizard, come back!" |
| SwitchIn | "Go! Blastoise!" |
| AbilityTriggered | "Blastoise's Intimidate!" |
| AbilityBlocked | "It doesn't affect Gengar... (Levitate)" |
| VolatileChanged | Silent (implementation detail) |
| StatChanged (from clearing) | Silent when stages is negative-of-current (clearing on switch) |

### Stat change phrasing

| Stages | Phrasing |
|--------|----------|
| +1 | "rose!" |
| +2 | "rose sharply!" |
| +3 or more | "rose drastically!" |
| -1 | "fell!" |
| -2 | "fell harshly!" |
| -3 or more | "fell severely!" |

### Where it lives

New package `com.pokemon.battle.render` — presentation concern, above engine.

### Rendering a full battle

A helper that takes a `BattleResult` and produces the complete text:

```kotlin
fun renderBattle(result: BattleResult, renderer: BattleRenderer): String
```

Walks each `TurnResult`, applies events, renders text, adds turn headers.

## Plan

### Step 1: BattleRenderer interface and TextRenderer implementation
- [ ] Interface with `render(event, stateBefore, stateAfter)` returning `List<String>`
- [ ] `TextRenderer` implementing all event types
- [ ] Helper function to look up Pokemon name from slot and state

### Step 2: renderBattle helper
- [ ] Walk turn history, apply events, render each, add turn headers
- [ ] Handle replacement events (between-turn)
- [ ] Return complete text

### Step 3: Tests
- [ ] Render a single turn (Charizard Flamethrowers Venusaur) — verify text matches
- [ ] Render status effects (paralysis, sleep, freeze) — verify correct phrasing
- [ ] Render stat changes (Swords Dance, Growl) — verify phrasing tiers
- [ ] Render switching — verify "come back" / "Go!" messages
- [ ] Render a full multi-turn battle via renderBattle — verify turn headers

### Step 4: Integration
- [ ] Use Pokedex + MoveDex to set up a battle, run it, render the output
- [ ] Print to stdout — the first human-readable battle output

## Validation

| Step | Validation | Result |
|------|-----------|--------|
| 1-2 | `./gradlew compileKotlin` | PASS |
| 3 | `./gradlew test` — 7 renderer tests | PASS |
| 4 | `./gradlew run` — first human-readable battle | PASS |
| All | 81 tests total, 0 failures | PASS |

## Sample output

```
--- Turn 1 ---
Charizard used Flamethrower!
It's super effective!
Venusaur took 133 damage! (155 → 22 HP)
Venusaur used Sludge Bomb!
Charizard took 70 damage! (153 → 83 HP)
--- Turn 2 ---
Charizard used Flamethrower!
It's super effective!
Venusaur took 133 damage! (22 → 0 HP)
Venusaur fainted!
Go! Blastoise!
--- Turn 3 ---
Charizard used Flamethrower!
It's not very effective...
Blastoise took 32 damage! (154 → 122 HP)
Blastoise used Sludge Bomb!
Charizard took 41 damage! (83 → 42 HP)
...
Side 2 wins!
```
