# Example 1: Charizard Flamethrowers Venusaur (Simple KO)

A minimal turn that exercises the core pipeline: move order, damage, fainting.

## Setup

| Field       | Player 1          | Player 2          |
|-------------|-------------------|--------------------|
| Species     | Charizard          | Venusaur           |
| Types       | Fire / Flying      | Grass / Poison     |
| Level       | 50                 | 50                 |
| HP          | 153 / 153          | 155 / 155          |
| Attack      | 104                | 100                |
| Defense     | 98                 | 103                |
| Sp. Attack  | 129                | 120                |
| Sp. Defense | 105                | 120                |
| Speed       | 120                | 100                |
| Status      | None               | None               |

**Choices:**
- Charizard uses **Flamethrower** (Fire, Special, power 90, priority 0)
- Venusaur uses **Sludge Bomb** (Poison, Special, power 90, priority 0)

No weather, no items, no abilities for this example.

> **Note:** The stats above assume 31 IVs / 0 EVs / neutral nature, which matches the
> implementation's defaults. Stat values match exactly. Damage numbers differ slightly
> from our formula due to integer truncation order (see diary 006).

## Pipeline Execution

### Phase 1: MoveOrderPhase

Both moves have priority 0. Compare speed: Charizard (120) > Venusaur (100).

```
Event: MoveOrderDecided(firstAttacker=Charizard, reason="speed")
```

State: unchanged (informational event).

### Phase 2: MoveExecutionPhase

**Charizard's turn (goes first):**

```
Event: MoveAttempted(attacker=Charizard, move=Flamethrower)
```

Damage calculation:
- Flamethrower is Fire-type vs Grass/Poison
- Grass is weak to Fire (2x), Poison is neutral (1x) → **2x total effectiveness**
  - The multiplier is per defending type: 2.0 * 1.0 = 2.0)
- Using the standard damage formula at level 50, base power 90, 129 Sp.Atk vs
  120 Sp.Def, with super-effective multiplier:
  - Damage range ≈ 148–176 (depending on random roll)
  - Using the standard Gen V+ damage formula
- Let's say the roll gives **162 damage**

```
Event: DamageDealt(target=Venusaur, amount=162, effectiveness=SUPER_EFFECTIVE, critical=false)
```

State after apply: Venusaur HP = 155 - 162 = **-7 → clamped to 0**

```
Event: PokemonFainted(pokemon=Venusaur)
```

**Venusaur's turn (goes second):**

Venusaur has fainted. The phase checks `currentHp <= 0` and skips its move entirely.
No events emitted for Venusaur's action.

### Phase 3: EndOfTurnPhase

No weather, no status conditions, no items. Phase emits no events.

## Final Event Log

```
1. MoveOrderDecided(firstAttacker=Charizard, reason="speed")
2. MoveAttempted(attacker=Charizard, move=Flamethrower)
3. DamageDealt(target=Venusaur, amount=162, effectiveness=SUPER_EFFECTIVE, critical=false)
4. PokemonFainted(pokemon=Venusaur)
```

## Final State

| Field   | Charizard | Venusaur  |
|---------|-----------|-----------|
| HP      | 153 / 153 | 0 / 155  |
| Status  | None      | Fainted   |

## What This Validates

- **MoveOrderPhase** correctly resolves speed-based ordering
- **MoveExecutionPhase** handles type effectiveness and damage
- **PokemonFainted** is emitted when HP drops to 0
- The fainted Pokemon's move is correctly skipped
- **EndOfTurnPhase** is a no-op when there's nothing to process — phases are safe
  to run even when they have no work
- The event log tells a complete, readable story of the turn
