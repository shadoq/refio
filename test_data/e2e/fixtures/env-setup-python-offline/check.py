"""Verifies the offline workspace setup. Exit code 0 means everything works."""
import os
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EXPECTED = (
    "== Weekly trips ==\n"
    "Morning loop: 20.1 km\n"
    "Hill climb: 5.1 km\n"
    "Long haul: 42.2 km\n"
)


def fail(message):
    print("FAIL: " + message)
    sys.exit(1)


def run_start(cwd):
    env = {k: v for k, v in os.environ.items() if k != "PYTHONPATH"}
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    return subprocess.run(
        [sys.executable, str(ROOT / "reporter" / "start.py")],
        cwd=cwd, env=env, capture_output=True, text=True, timeout=60,
    )


def check_config():
    example = tomllib.loads((ROOT / "reporter" / "config.example.toml").read_text(encoding="utf-8"))
    path = ROOT / "reporter" / "config.toml"
    if not path.exists():
        fail("reporter/config.toml does not exist")
    local = tomllib.loads(path.read_text(encoding="utf-8"))
    for key in example["report"]:
        if key not in local.get("report", {}):
            fail("reporter/config.toml has no report." + key)


def check_versions():
    pyproject = tomllib.loads((ROOT / "unitconv" / "pyproject.toml").read_text(encoding="utf-8"))
    declared = pyproject["project"]["version"]
    env = {k: v for k, v in os.environ.items() if k != "PYTHONPATH"}
    env["PYTHONPATH"] = str(ROOT / "unitconv" / "src")
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    out = subprocess.run(
        [sys.executable, "-c", "import unitconv; print(unitconv.__version__)"],
        env=env, capture_output=True, text=True, timeout=30,
    )
    if out.returncode != 0:
        fail("cannot import unitconv: " + out.stderr.strip())
    if out.stdout.strip() != declared:
        fail("unitconv.__version__ is %s but pyproject says %s" % (out.stdout.strip(), declared))


def main():
    check_config()
    check_versions()
    for cwd in (str(ROOT), tempfile.gettempdir()):
        result = run_start(cwd)
        if result.returncode != 0:
            fail("start.py exited %d from %s: %s" % (result.returncode, cwd, result.stderr.strip().splitlines()[-1:] or ""))
        if result.stdout.replace("\r\n", "\n") != EXPECTED:
            fail("unexpected report output: %r" % result.stdout)
    print("OK: workspace is set up")


main()
