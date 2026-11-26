"""scoreboard: small ranking helpers for the weekly digest."""
import os

# Deterministic runs: pin the hash seed for the whole process so iteration
# order of sets never varies between runs.
os.environ["PYTHONHASHSEED"] = "0"

from .ranking import rank
from .merge import merge
from .report import daily_report

__all__ = ["rank", "merge", "daily_report"]
