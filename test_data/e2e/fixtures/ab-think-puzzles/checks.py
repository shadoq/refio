#!/usr/bin/env python3
"""Acceptance gate: simulate both puzzle plans and enforce every constraint
plus the optimal totals (7 crossings; 17 minutes)."""
import json
import sys

LEFT_ITEMS = ("wolf", "goat", "cabbage")
BRIDGE_TIMES = {"1": 1, "2": 2, "5": 5, "10": 10}


def bad_unsupervised(side):
    """True when something gets eaten on `side` (farmer not there)."""
    if "wolf" in side and "goat" in side:
        return "wolf eats goat"
    if "goat" in side and "cabbage" in side:
        return "goat eats cabbage"
    return None


def check_wolf():
    try:
        with open("plan/wolf.json", encoding="utf-8") as fh:
            plan = json.load(fh)
    except OSError:
        return "plan/wolf.json is missing"
    except ValueError as exc:
        return f"plan/wolf.json does not parse: {exc}"
    if not isinstance(plan, list) or len(plan) != 7:
        return f"the optimal crossing plan has exactly 7 steps, got {len(plan) if isinstance(plan, list) else type(plan).__name__}"
    left = {"farmer", *LEFT_ITEMS}
    right = set()
    for i, carried in enumerate(plan):
        if not isinstance(carried, str) or carried not in ("", *LEFT_ITEMS):
            return f"step {i + 1}: {carried!r} is not a carryable item or an empty string"
        to_right = i % 2 == 0
        src, dst = (left, right) if to_right else (right, left)
        if "farmer" not in src:
            return f"step {i + 1}: farmer is not on the source bank"
        if carried:
            if carried not in src:
                return f"step {i + 1}: item {carried!r} is not on the source bank"
            src.discard(carried)
            dst.add(carried)
        src.discard("farmer")
        dst.add("farmer")
        for bank, side_name in ((left, "left"), (right, "right")):
            if "farmer" not in bank:
                trouble = bad_unsupervised(bank)
                if trouble:
                    return f"step {i + 1}: {trouble} on the {side_name} bank"
    if right != {"farmer", *LEFT_ITEMS}:
        return "the final state does not have farmer, wolf, goat and cabbage on the right bank"
    return None


def check_bridge():
    try:
        with open("plan/bridge.json", encoding="utf-8") as fh:
            plan = json.load(fh)
    except OSError:
        return "plan/bridge.json is missing"
    except ValueError as exc:
        return f"plan/bridge.json does not parse: {exc}"
    if not isinstance(plan, list) or not plan:
        return "plan/bridge.json must be a non-empty array of crossings"
    left = {"1", "2", "5", "10"}
    right = set()
    total = 0
    torch_left = True
    for i, crossing in enumerate(plan):
        if not isinstance(crossing, list) or not (1 <= len(crossing) <= 2):
            return f"crossing {i + 1}: must be an array of 1 or 2 person ids"
        ids = [str(x) for x in crossing]
        for pid in ids:
            if pid not in BRIDGE_TIMES:
                return f"crossing {i + 1}: unknown person {pid!r}"
        src, dst = (left, right) if torch_left else (right, left)
        if not all(pid in src for pid in ids):
            return f"crossing {i + 1}: someone is not on the torch side"
        for pid in ids:
            src.discard(pid)
            dst.add(pid)
        total += max(BRIDGE_TIMES[pid] for pid in ids)
        torch_left = not torch_left
    if right != {"1", "2", "5", "10"}:
        return "not everyone ended up on the far side"
    if total != 17:
        return f"total crossing time is {total} minutes, the optimal is exactly 17"
    return None


def main() -> int:
    problems = []
    wolf_error = check_wolf()
    if wolf_error:
        problems.append(f"wolf: {wolf_error}")
    bridge_error = check_bridge()
    if bridge_error:
        problems.append(f"bridge: {bridge_error}")
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: both plans are legal and optimal (7 crossings; 17 minutes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
