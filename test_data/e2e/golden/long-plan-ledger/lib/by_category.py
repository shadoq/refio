from lib.parse_entry import parse_entry


def by_category(lines):
    out = {}
    for l in lines:
        e = parse_entry(l)
        out[e["category"]] = round(out.get(e["category"], 0) + e["amount"], 2)
    return out
