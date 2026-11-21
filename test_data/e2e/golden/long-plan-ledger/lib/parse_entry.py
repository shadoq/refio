def parse_entry(line):
    parts = line.strip().split(";")
    if len(parts) != 4:
        raise ValueError(f"bad entry: {line!r}")
    date, category, amount, note = parts
    return {"date": date, "category": category, "amount": float(amount), "note": note}
