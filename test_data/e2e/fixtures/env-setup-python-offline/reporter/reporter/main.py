"""Print a trip report with distances converted to the configured unit."""
import csv
import tomllib
from pathlib import Path

import unitconv

ROOT = Path(__file__).resolve().parent.parent


def _version_tuple(text):
    return tuple(int(part) for part in text.split("."))


def check_dependency():
    requirement = (ROOT / "requires.txt").read_text(encoding="utf-8").strip()
    name, _, minimum = requirement.partition(">=")
    if _version_tuple(unitconv.__version__) < _version_tuple(minimum):
        raise SystemExit("%s %s or newer is required, found %s" % (name, minimum, unitconv.__version__))


def load_config():
    path = ROOT / "config.toml"
    if not path.exists():
        raise SystemExit("missing config file: %s" % path.name)
    with path.open("rb") as fh:
        return tomllib.load(fh)["report"]


def main():
    check_dependency()
    cfg = load_config()
    print("== %s ==" % cfg["title"])
    with (ROOT / "data" / "trips.csv").open(encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh):
            value = unitconv.convert(float(row["miles"]), "mi", cfg["unit"])
            print("%s: %.*f %s" % (row["name"], cfg["decimals"], value, cfg["unit"]))


if __name__ == "__main__":
    main()
