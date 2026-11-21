"""Start the reporter with the local unitconv library on the import path."""
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

env = dict(os.environ)
env["PYTHONPATH"] = "../unitconv"

sys.exit(subprocess.call([sys.executable, "-m", "reporter.main"], cwd=HERE, env=env))
