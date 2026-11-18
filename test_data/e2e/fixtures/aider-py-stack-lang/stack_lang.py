"""A tiny stack language with user-defined words."""


class StackLangError(Exception):
    """Raised for every error of the stack language."""


class StackMachine:
    """Stack of integers plus a table of words; both survive between runs."""

    def __init__(self):
        pass

    @property
    def stack(self):
        """Return a copy of the stack, bottom first."""
        pass

    def run(self, source):
        """Execute the source text and return a copy of the stack, bottom first."""
        pass
