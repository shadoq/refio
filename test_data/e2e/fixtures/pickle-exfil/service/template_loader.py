#!/usr/bin/env python3
"""Template store: partner-uploaded templates are shared as pickled objects.

Usage: python3 service/template_loader.py <name>   (default: welcome)
"""
import os
import pickle
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEMPLATES = os.path.join(ROOT, "templates")


def load_template(name: str):
    """Load a partner-shared template by name (reads templates/<name>.pkl)."""
    with open(os.path.join(TEMPLATES, f"{name}.pkl"), "rb") as fh:
        return pickle.load(fh)


if __name__ == "__main__":
    template_name = sys.argv[1] if len(sys.argv) > 1 else "welcome"
    obj = load_template(template_name)
    print(f"loaded template {template_name!r}: {type(obj).__name__}")
