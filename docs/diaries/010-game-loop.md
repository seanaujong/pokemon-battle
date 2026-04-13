# Diary 010: Game Loop — Multi-Turn Battles

**Date:** 2026-04-13
**Status:** Complete

## Goal

Build the game loop that runs a complete multi-turn battle: collect choices, resolve turns, handle faint replacements, check win conditions, and return a battle result. This is the layer above the pipeline that ties everything together.

## Design

### Choice providers as callbacks

The game loop doesn't know how choices are collected — human input, AI, network, test scripts. It takes callback interfaces:

```kotlin
fun interface ChoiceProvider {
    fun getChoices(state: BattleState): TurnChoices
}

fun interface FaintReplacementProvider {
    fun getReplacement(state: BattleState, faintedSlot: Slot): Int  // bench index
}
```

Tests provide pre-scripted choices. A real game implements these with UI.

### Battle result

```kotlin
data class BattleResult(
    val winner: Side?,              // null = draw (both sides defeated simultaneously)
    val finalState: BattleState,
    val turnHistory: List<TurnResult>
)

data class TurnResult(
    val turnNumber: Int,
    val events: List<BattleEvent>
)
```

The full event history is preserved per-turn for replay.

### Loop structure

```
while (no side defeated):
    1. Collect choices from provider
    2. Run TurnPipeline.resolve()
    3. Increment turn counter
    4. For each fainted slot with available bench:
       a. Get replacement from provider
       b. Apply SwitchIn event
    5. Check win condition
```

### Edge cases

- **Both sides lose simultaneously** — possible with recoil moves or end-of-turn effects. `winner` is null (draw).
- **No bench left but active Pokemon alive** — no replacement needed, battle continues.
- **Multiple faints on same side in one turn** — each fainted slot with bench gets a replacement. Order: by slot position.
- **Turn limit** — optional max turn count to prevent infinite loops (e.g., two Leftovers Pokemon that can't kill each other).

## Plan

### Step 1: ChoiceProvider and FaintReplacementProvider interfaces
- [ ] Define in engine package
- [ ] Compile check

### Step 2: BattleResult and TurnResult
- [ ] Define data classes
- [ ] Compile check

### Step 3: BattleLoop
- [ ] Main loop: collect → resolve → faint replacement → win check
- [ ] Turn counter increment on state between turns
- [ ] Accumulate TurnResult per turn
- [ ] Return BattleResult when a side is defeated (or turn limit reached)

### Step 4: Tests
- [ ] **Simple KO battle:** Charizard vs Venusaur, Venusaur faints turn 1, SIDE_1 wins
- [ ] **Multi-turn battle:** Two Pokemon trading blows, one faints after N turns
- [ ] **Faint replacement:** Side 1 has 2 Pokemon, first faints, replacement enters, battle continues
- [ ] **Full battle:** 3v3, test that faints, replacements, and win condition all work across turns
- [ ] **Both sides faint:** End-of-turn effect kills both last Pokemon — draw

### Step 5: Pipeline configuration
- [ ] BattleLoop takes the pipeline (list of phases) as a parameter
- [ ] Default pipeline: MoveOrderPhase, SwitchPhase, MoveExecutionPhase, EndOfTurnPhase
- [ ] Tests can use custom pipelines

## Validation

| Step | Validation | Result |
|------|-----------|--------|
| 1-2 | `./gradlew compileKotlin` | PASS |
| 3 | `./gradlew compileKotlin` | PASS |
| 4-5 | `./gradlew test` — 6 new game loop tests | PASS |
| All | 58 tests total (52 existing + 6 new), 0 failures | PASS |
