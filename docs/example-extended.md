# Example 2: Priority, Status, Weather, and End-of-Turn Effects

A turn that exercises multiple pipeline phases and several event types to validate
that the architecture can handle interacting mechanics.

## Setup

| Field       | Player 1            | Player 2              |
|-------------|---------------------|-----------------------|
| Species     | Infernape           | Swampert              |
| Types       | Fire / Fighting     | Water / Ground        |
| Level       | 50                  | 50                    |
| HP          | 152 / 152           | 172 / 172             |
| Attack      | 124                 | 130                   |
| Defense     | 91                  | 110                   |
| Sp. Attack  | 124                 | 105                   |
| Sp. Defense | 91                  | 110                   |
| Speed       | 128                 | 80                    |
| Status      | None                | **Burn** (pre-existing)|
| Item        | None                | **Leftovers**         |

**Field conditions:** Sandstorm is active (3 turns remaining).

**Choices:**
- Infernape uses **Mach Punch** (Fighting, Physical, power 40, **priority +1**)
- Swampert uses **Earthquake** (Ground, Physical, power 100, priority 0)

## Pipeline Execution

### Phase 1: MoveOrderPhase

Mach Punch has priority +1, Earthquake has priority 0. Priority bracket wins
regardless of speed.

```
Event: MoveOrderDecided(firstAttacker=Infernape, reason="priority")
```

### Phase 2: MoveExecutionPhase

**Infernape's turn (goes first — priority):**

```
Event: MoveAttempted(attacker=Infernape, move=Mach Punch)
```

Damage calculation:
- Mach Punch is Fighting-type vs Water/Ground
- Water is neutral to Fighting (1x), Ground is neutral to Fighting (1x) → 1x
- Level 50, base power 40, 124 Atk vs 110 Def, no multiplier
- Damage ≈ 28–34. Roll gives **30 damage**

```
Event: DamageDealt(target=Swampert, amount=30, effectiveness=NEUTRAL, critical=false)
```

State: Swampert HP = 172 - 30 = **142**

**Swampert's turn (goes second):**

Swampert is burned. Before executing, check if burn prevents action — it doesn't
(burn doesn't prevent action, it halves physical attack damage). Swampert can act.

```
Event: MoveAttempted(attacker=Swampert, move=Earthquake)
```

Damage calculation:
- Earthquake is Ground-type vs Fire/Fighting
- Fire is weak to Ground (2x), Fighting is neutral to Ground (1x) → **2x total**
- Level 50, base power 100, 130 Atk vs 91 Def
- **Burn halves physical damage** → effective Atk = 65 for this calculation
- Super-effective 2x, but burn halving offsets it
- Damage ≈ 100–120. Roll gives **110 damage**

```
Event: DamageDealt(target=Infernape, amount=110, effectiveness=SUPER_EFFECTIVE, critical=false)
```

State: Infernape HP = 152 - 110 = **42**

Neither Pokemon fainted. No `PokemonFainted` events.

### Phase 3: EndOfTurnPhase

End-of-turn effects are resolved in a specific order. This is where extensibility
gets tested — multiple independent mechanics each emit their own events.

**3a. Weather damage (Sandstorm):**

Sandstorm damages all Pokemon not Rock, Ground, or Steel type.

- Infernape is Fire/Fighting → takes sandstorm damage: 152 / 16 = **9 damage** (rounded down)
- Swampert is Water/Ground → **immune** to sandstorm damage

```
Event: WeatherDamage(target=Infernape, amount=9, weather=SANDSTORM)
```

State: Infernape HP = 42 - 9 = **33**

**3b. Status damage (Burn):**

Burn deals 1/16 max HP per turn.

- Swampert has burn: 172 / 16 = **10 damage**

```
Event: StatusDamage(target=Swampert, amount=10, source=BURN)
```

State: Swampert HP = 142 - 10 = **132**

**3c. Item effects (Leftovers):**

Leftovers restores 1/16 max HP per turn.

- Swampert holds Leftovers: 172 / 16 = **10 HP restored**

```
Event: ItemHealing(target=Swampert, amount=10, item=LEFTOVERS)
```

State: Swampert HP = 132 + 10 = **142**

(Burn and Leftovers cancel out this turn — a nice emergent interaction that the
event log makes visible.)

**3d. Weather countdown:**

```
Event: WeatherTick(weather=SANDSTORM, turnsRemaining=2)
```

## Final Event Log

```
 1. MoveOrderDecided(firstAttacker=Infernape, reason="priority")
 2. MoveAttempted(attacker=Infernape, move=Mach Punch)
 3. DamageDealt(target=Swampert, amount=30, effectiveness=NEUTRAL, critical=false)
 4. MoveAttempted(attacker=Swampert, move=Earthquake)
 5. DamageDealt(target=Infernape, amount=110, effectiveness=SUPER_EFFECTIVE, critical=false)
 6. WeatherDamage(target=Infernape, amount=9, weather=SANDSTORM)
 7. StatusDamage(target=Swampert, amount=10, source=BURN)
 8. ItemHealing(target=Swampert, amount=10, item=LEFTOVERS)
 9. WeatherTick(weather=SANDSTORM, turnsRemaining=2)
```

## Final State

| Field   | Infernape    | Swampert     |
|---------|--------------|--------------|
| HP      | 33 / 152     | 142 / 172    |
| Status  | None         | Burn         |
| Item    | None         | Leftovers    |

**Field:** Sandstorm (2 turns remaining)

## What This Validates

### Priority works independently of speed
Infernape is faster anyway, but it went first because of Mach Punch's +1 priority,
not speed. The `reason` field in `MoveOrderDecided` makes this explicit and testable.

### Status conditions modify damage without new phases
Burn's attack halving is handled inside `MoveExecutionPhase` during damage
calculation — no special "burn phase" needed. The status is just data on the
`PokemonState` that the damage formula reads.

### End-of-turn is a single phase with multiple concerns
Weather damage, status damage, and item healing are all handled by `EndOfTurnPhase`.
Each produces its own event type. The phase iterates through sub-concerns in the
correct order. If we later add more end-of-turn effects (Leech Seed, Wish, Aqua
Ring, etc.), they slot into this same phase.

### New event types extend the system cleanly
This example required three event types not in the simple example:
- `WeatherDamage` — needed `weather` field and sandstorm immunity logic
- `ItemHealing` — a new event with its own `apply()` that increases HP
- `WeatherTick` — tracks weather duration

None of these required changes to `DamageDealt`, `MoveAttempted`, or any existing
event. The pipeline and phase interfaces didn't change either.

### The event log reveals emergent interactions
Burn damage (event 7) and Leftovers healing (event 8) perfectly cancel. This isn't
special-cased anywhere — it falls out naturally from the event sequence. The log
makes this visible in a way that a final-state-only system would obscure.

### The order of events is deterministic and auditable
A test can assert not just "Infernape has 33 HP" but the exact sequence:
took 110 from Earthquake, then 9 from sandstorm = 33. If a bug causes sandstorm
to tick before move execution, the event order would catch it immediately.
