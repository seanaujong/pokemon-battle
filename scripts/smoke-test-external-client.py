#!/usr/bin/env python3
"""
External-language smoke test for the :server module (diary 069).

Subprocesses the JVM server, submits two Smogon-format teams, then drives a full
battle turn by turn. Each turn the script picks a random legal move from the
pool the server advertised. Exits 0 if the server emits a `result` message
before the turn limit; non-zero otherwise.

This is the litmus test for whether the JVM engine's module boundaries hold up
for an out-of-JVM consumer. The script intentionally uses only stdlib — no
Python package for the engine, no Kotlin knowledge leaking across the boundary.

Usage:
    # Build the server distribution first:
    ./gradlew :server:installDist
    # Then run the smoke test:
    python3 scripts/smoke-test-external-client.py
"""

from __future__ import annotations

import json
import os
import random
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SERVER_BIN = REPO_ROOT / "server" / "build" / "install" / "server" / "bin" / "server"
PROTOCOL_VERSION = 1

TEAM_SIDE_1 = """\
Charizard @ Life Orb
Ability: Blaze
Level: 100
- Flamethrower
- Earthquake
- Thunderbolt

Garchomp
Ability: Sand Veil
Level: 100
- Earthquake
- Ice Beam
"""

TEAM_SIDE_2 = """\
Venusaur
Ability: Overgrow
Level: 100
- Sludge Bomb
- Earthquake

Blastoise
Ability: Torrent
Level: 100
- Ice Beam
- Sludge Bomb
"""


def send(proc: subprocess.Popen, message: dict) -> None:
    line = json.dumps(message)
    proc.stdin.write(line + "\n")
    proc.stdin.flush()


def recv(proc: subprocess.Popen) -> dict:
    line = proc.stdout.readline()
    if not line:
        stderr = proc.stderr.read() if proc.stderr else ""
        raise RuntimeError(f"server closed stream; stderr:\n{stderr}")
    return json.loads(line)


def pick_choice(slot_summary: dict) -> dict:
    """Random legal move from the slot's advertised move pool."""
    move = random.choice(slot_summary["moves"])
    return {"type": "use_move", "move": move}


def run() -> int:
    if not SERVER_BIN.exists():
        print(
            f"Server binary not found at {SERVER_BIN}. Run "
            f"'./gradlew :server:installDist' first.",
            file=sys.stderr,
        )
        return 2

    proc = subprocess.Popen(
        [str(SERVER_BIN)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env={**os.environ, "JAVA_OPTS": "-Xmx256m"},
    )

    try:
        send(
            proc,
            {
                "type": "team_set",
                "side": "SIDE_1",
                "team": TEAM_SIDE_1,
                "protocolVersion": PROTOCOL_VERSION,
            },
        )
        send(
            proc,
            {
                "type": "team_set",
                "side": "SIDE_2",
                "team": TEAM_SIDE_2,
                "protocolVersion": PROTOCOL_VERSION,
            },
        )

        ready = recv(proc)
        assert ready["type"] == "ready", f"expected ready, got {ready}"
        # slots[].moves are full Move objects (with type/category/power), ready to
        # echo back verbatim in a use_move choice.
        slots_by_side = {s["slot"]["side"]: s for s in ready["slots"]}
        benches_by_side: dict[str, list[dict]] = {}
        for b in ready["benches"]:
            benches_by_side.setdefault(b["side"], []).append(b)

        print(
            f"ready: {ready['slots'][0]['species']} (side 1) vs "
            f"{ready['slots'][1]['species']} (side 2)"
        )

        turn_count = 0
        while True:
            message = recv(proc)
            kind = message["type"]

            if kind == "choice_request":
                # Build a choice per active slot from the current move pools.
                entries = []
                for slot in message["activeSlots"]:
                    summary = slots_by_side[slot["side"]]
                    entries.append({"slot": slot, "choice": pick_choice(summary)})
                send(
                    proc,
                    {
                        "type": "choice",
                        "choices": {"entries": entries},
                        "protocolVersion": PROTOCOL_VERSION,
                    },
                )

            elif kind == "turn_events":
                turn_count += 1
                # Refresh active move pools for any side whose active slot just
                # changed (via self-switch or replacement). A naive but correct
                # approach: look for switch_in events in this turn and promote the
                # bench member that came in.
                promote_switches(message.get("events", []), slots_by_side, benches_by_side)
                promote_switches(
                    message.get("replacementEvents", []),
                    slots_by_side,
                    benches_by_side,
                )

            elif kind == "input_request":
                request = message["request"]
                if request["type"] == "switch_target_request":
                    # Pick the first eligible bench slot deterministically.
                    bench_index = request["eligibleBenchIndices"][0]
                    send(
                        proc,
                        {
                            "type": "input_response",
                            "response": {
                                "type": "switch_target_response",
                                "benchIndex": bench_index,
                            },
                            "protocolVersion": PROTOCOL_VERSION,
                        },
                    )
                else:
                    raise RuntimeError(f"unsupported input request: {request}")

            elif kind == "faint_replacement_request":
                bench_index = message["eligibleBenchIndices"][0]
                send(
                    proc,
                    {
                        "type": "faint_replacement",
                        "slot": message["slot"],
                        "benchIndex": bench_index,
                        "protocolVersion": PROTOCOL_VERSION,
                    },
                )

            elif kind == "result":
                print(
                    f"result: winner={message['winner']} after {message['turns']} turns "
                    f"(client saw {turn_count} turn_events)"
                )
                return 0

            elif kind == "error":
                print(f"server error: {message['message']}", file=sys.stderr)
                return 1

            else:
                raise RuntimeError(f"unknown server message: {kind}")

    finally:
        try:
            proc.stdin.close()
        except Exception:
            pass
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


def promote_switches(
    events: list[dict],
    slots_by_side: dict[str, dict],
    benches_by_side: dict[str, list[dict]],
) -> None:
    """
    When a SwitchIn event fires, swap the active slot's species/moves with the
    chosen bench member. Keeps the client's view of the legal move pool in sync
    with the engine's current active Pokemon.
    """
    for event in events:
        if event.get("type") != "SwitchInJson":
            # The BattleEventJson hierarchy keeps its default class-name discriminators
            # since consumers that care about events (replay, analytics) shouldn't
            # depend on short names. That's fine for the smoke test — we only match
            # the one variant we care about.
            continue
        slot = event["slot"]
        bench_index = event["benchIndex"]
        bench = benches_by_side.get(slot["side"], [])
        if bench_index >= len(bench):
            continue
        new_member = bench[bench_index]
        old_active = slots_by_side[slot["side"]]
        # Swap: the old active becomes the bench member at this index,
        # the new member becomes the active. This matches how the engine's
        # SwitchIn.apply() updates BattleState.
        bench[bench_index] = {
            "side": slot["side"],
            "index": bench_index,
            "species": old_active["species"],
            "maxHp": old_active["maxHp"],
            "moves": old_active["moves"],
        }
        slots_by_side[slot["side"]] = {
            "slot": slot,
            "species": new_member["species"],
            "maxHp": new_member["maxHp"],
            "moves": new_member["moves"],
        }


if __name__ == "__main__":
    sys.exit(run())
