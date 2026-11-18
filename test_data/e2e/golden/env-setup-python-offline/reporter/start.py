"""Start the reporter with the local unitconv library on the import path."""
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
LIB = HERE.parent / "unitconv" / "src"

env = dict(os.environ)
env["PYTHONPATH"] = os.pathsep.join([str(LIB)] + ([env["PYTHONPATH"]] if env.get("PYTHONPATH") else []))

sys.exit(subprocess.call([sys.executable, "-m", "reporter.main"], cwd=HERE, env=env))
