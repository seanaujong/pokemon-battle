#!/usr/bin/env python3
"""
Render a persisted battle as a human-readable log. Reads the JSON format
documented in ``docs/corpus-format.md``; writes a turn-by-turn transcript to
stdout.

This script exists to test a claim made in diary 085: the event format is
self-describing enough that a non-JVM consumer can render our battles with
zero re-port of engine logic. If this script breaks, the claim is wrong.

Deliberately minimal — no HP bar tracking, no state reconstruction, no animation.
Just: parse JSON, walk the event list, print a line per event. Anything fancier
would duplicate TextRenderer's logic in Python, which is exactly the
re-implementation the corpus format is meant to avoid.

Usage:
    python3 scripts/battle-log.py battles/<battle-id>.json
    python3 scripts/battle-log.py battles/   # first file in dir (smoke test)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

# Event-type short labels. Keys are the suffix of the DTO class name in the
# persisted `type` field (e.g. "MoveAttemptedJson"). We key off the short
# suffix so this script doesn't care whether the DTO's package path ever
# changes.
EVENT_LABELS: dict[str, str] = {
    "MoveOrderDecidedJson": "order",
    "MoveAttemptedJson": "move",
    "MoveFailedJson": "fail",
    "CriticalHitJson": "crit",
    "DamageDealtJson": "damage",
    "PokemonFaintedJson": "FAINT",
    "ProtectBlockedJson": "protect",
    "StatChangedJson": "stat",
    "TypeChangedJson": "type-change",
    "VolatileAddedJson": "vol+",
    "VolatileRemovedJson": "vol-",
    "StatusAppliedJson": "status+",
    "StatusClearedJson": "status-",
    "StatusDamageJson": "status-dmg",
    "WeatherSetJson": "weather",
    "WeatherTickJson": "weather-tick",
    "WeatherDamageJson": "weather-dmg",
    "TrickRoomSetJson": "trick-room",
    "ItemHealingJson": "heal",
    "ItemConsumedJson": "consumed",
    "ItemDamageJson": "item-dmg",
    "SwitchInJson": "switch-in",
    "SwitchOutJson": "switch-out",
    "AbilityTriggeredJson": "ability",
    "AbilityBlockedJson": "ability-blocked",
    "SideConditionSetJson": "side+",
    "SideConditionTickJson": "side-tick",
    "SideConditionExpiredJson": "side-",
    "GimmickUsedJson": "gimmick",
    "HazardSetJson": "hazard+",
    "HazardRemovedJson": "hazard-",
    "HazardDamageJson": "hazard-dmg",
    "TurnPausedForInputJson": "pause",
    "TurnInputResolvedJson": "resume",
}


def short_type(event: dict) -> str:
    full = event.get("type", "?")
    return full.rsplit(".", 1)[-1]


def label(event: dict) -> str:
    return EVENT_LABELS.get(short_type(event), short_type(event))


def describe(event: dict) -> str:
    kind = short_type(event)
    if kind == "MoveAttemptedJson":
        slot = side(event.get("attacker", {}))
        move = event.get("move", {}).get("name", "?")
        return f"{slot} used {move}"
    if kind == "DamageDealtJson":
        target = side(event.get("target", {}))
        amount = event.get("amount", 0)
        eff = event.get("effectiveness", "NEUTRAL")
        return f"{target} took {amount} ({eff.lower()})"
    if kind == "CriticalHitJson":
        target = side(event.get("target", {}))
        return f"critical hit on {target}"
    if kind == "PokemonFaintedJson":
        return f"{side(event.get('slot', {}))} fainted"
    if kind == "SwitchInJson":
        return f"{side(event.get('slot', {}))} switched in (bench #{event.get('benchIndex')})"
    if kind == "SwitchOutJson":
        return f"{side(event.get('slot', {}))} switched out"
    if kind == "AbilityTriggeredJson":
        return f"{side(event.get('slot', {}))} triggered {event.get('ability')}"
    if kind == "StatChangedJson":
        stages = event.get("stages", 0)
        arrow = "↑" if stages > 0 else "↓"
        return f"{side(event.get('target', {}))}: {event.get('stat')} {arrow}{abs(stages)}"
    if kind == "StatusAppliedJson":
        return f"{side(event.get('target', {}))} got {event.get('status')}"
    if kind == "StatusClearedJson":
        return f"{side(event.get('target', {}))} cured of {event.get('status')}"
    if kind == "WeatherSetJson":
        return f"weather → {event.get('weather')} ({event.get('turnsRemaining')} turns)"
    if kind == "ItemDamageJson":
        return f"{side(event.get('target', {}))} hurt by {event.get('item')} ({event.get('amount')})"
    if kind == "ItemHealingJson":
        return f"{side(event.get('target', {}))} healed by {event.get('item')} ({event.get('amount')})"
    if kind == "MoveOrderDecidedJson":
        order = [side(s) for s in event.get("order", [])]
        return f"order: {' → '.join(order)}"
    # Fallback: dump the fields minus the type discriminator.
    fields = {k: v for k, v in event.items() if k != "type"}
    return f"{kind}: {fields}" if fields else kind


def side(slot: dict) -> str:
    s = slot.get("side", "?")
    pos = slot.get("position", 0)
    return s if pos == 0 else f"{s}[{pos}]"


def render(battle: dict) -> None:
    meta = battle.get("metadata", {})
    tags = meta.get("playerTags", {}) or {}
    print(f"Battle {meta.get('battleId', '?')}")
    print(f"  Format:  {meta.get('formatTag', '?')}")
    if tags:
        print(f"  Side 1:  {tags.get('SIDE_1', '(untagged)')}")
        print(f"  Side 2:  {tags.get('SIDE_2', '(untagged)')}")
    if meta.get("clientInfo"):
        print(f"  Client:  {meta['clientInfo']}")
    print()

    for turn in battle.get("turns", []):
        n = turn.get("turnNumber", "?")
        print(f"--- Turn {n} ---")
        for ev in turn.get("events", []):
            print(f"  [{label(ev):12s}] {describe(ev)}")
        for ev in turn.get("replacementEvents", []):
            print(f"  [{'replace':12s}] {describe(ev)}")
        print()

    winner = battle.get("winner")
    print(f"Result: {'draw / turn limit' if winner is None else f'{winner} wins'}")


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("Usage: battle-log.py <battle-json-file | battle-dir>", file=sys.stderr)
        return 2
    path = Path(argv[1])
    if path.is_dir():
        candidates = sorted(path.glob("*.json"))
        if not candidates:
            print(f"No .json files in {path}", file=sys.stderr)
            return 1
        path = candidates[0]
        print(f"(reading first file: {path.name})")
    if not path.exists():
        print(f"File not found: {path}", file=sys.stderr)
        return 1
    battle = json.loads(path.read_text())
    render(battle)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
