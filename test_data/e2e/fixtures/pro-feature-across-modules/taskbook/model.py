"""Task data model."""
from dataclasses import dataclass
from datetime import date
from typing import Optional


@dataclass
class Task:
    id: int
    title: str
    due: Optional[date] = None
    done: bool = False
