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
    snoozed_until: Optional[date] = None

    def is_snoozed(self, today):
        return not self.done and self.snoozed_until is not None and self.snoozed_until >= today
