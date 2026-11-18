"""League table: tally match results and print the standings."""

HEADER = f"{'Team':<30} | MP |  W |  D |  L |  GD |  P"


def tally(rows):
    """Return the formatted league table for the given match result lines."""
    stats = {}
    for number, raw in enumerate(rows, start=1):
        line = raw.strip()
        if not line:
            continue
        parts = line.split(";")
        if len(parts) != 3:
            raise ValueError(f"line {number}: expected 'home;away;score'")
        home, away, score = (part.strip() for part in parts)
        goals = score.split("-")
        if not home or not away or home == away or len(goals) != 2 or not all(g.isdigit() for g in goals):
            raise ValueError(f"line {number}: malformed result")
        home_goals, away_goals = int(goals[0]), int(goals[1])
        for team, scored, conceded in ((home, home_goals, away_goals), (away, away_goals, home_goals)):
            entry = stats.setdefault(team, {"mp": 0, "w": 0, "d": 0, "l": 0, "gf": 0, "ga": 0, "p": 0})
            entry["mp"] += 1
            entry["gf"] += scored
            entry["ga"] += conceded
            if scored > conceded:
                entry["w"] += 1
                entry["p"] += 3
            elif scored == conceded:
                entry["d"] += 1
                entry["p"] += 1
            else:
                entry["l"] += 1

    def key(team):
        entry = stats[team]
        return (-entry["p"], -(entry["gf"] - entry["ga"]), -entry["gf"], team)

    lines = [HEADER]
    for team in sorted(stats, key=key):
        e = stats[team]
        gd = e["gf"] - e["ga"]
        lines.append(f"{team:<30} | {e['mp']:>2} | {e['w']:>2} | {e['d']:>2} | {e['l']:>2} | {gd:>+3} | {e['p']:>2}")
    return "\n".join(lines)
