from lib.parse_entry import parse_entry


def balance(lines):
    return round(sum(parse_entry(l)["amount"] for l in lines), 2)
