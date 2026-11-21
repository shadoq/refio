from lib.parse_entry import parse_entry


def monthly_totals(lines):
    out = {}
    for l in lines:
        e = parse_entry(l)
        out[e["date"][:7]] = round(out.get(e["date"][:7], 0) + e["amount"], 2)
    return sorted(out.items())
