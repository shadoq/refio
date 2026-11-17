"""WRONG: slugify replaces each bad character separately instead of collapsing runs."""
import re


def flatten_once(nested):
    out = []
    for item in nested:
        if isinstance(item, list):
            out.extend(item)
        else:
            out.append(item)
    return out


def slugify(title):
    lowered = title.lower()
    dashed = re.sub(r"[^a-z0-9]", "-", lowered)
    return dashed.strip("-")


def mask_email(email):
    local, _, domain = email.partition("@")
    return f"{local[0]}***@{domain}"
