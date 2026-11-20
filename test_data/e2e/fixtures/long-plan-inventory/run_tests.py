"""Runs every tests/test_*.py module's run(); one PASS or FAILED line each, then a summary."""
import importlib
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
names = sorted(p.stem for p in pathlib.Path(__file__).parent.joinpath("tests").glob("test_*.py"))
passed = 0
for name in names:
    try:
        importlib.import_module(f"tests.{name}").run()
        passed += 1
        print(f"PASS {name}")
    except Exception as exc:  # noqa: BLE001 - every failure is reported, none stops the run
        print(f"FAILED {name}: {str(exc).splitlines()[0] if str(exc) else type(exc).__name__}")
print(f"SUMMARY passed {passed}/{len(names)}")
sys.exit(0 if passed == len(names) else 1)
