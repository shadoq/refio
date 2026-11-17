"""Small text utilities - complete the three documented functions.

The docstrings are the contract: examples are exact. The unit tests in
test_textutils.py are the acceptance bar (they are not to be modified).
"""
import re


def flatten_once(nested):
    """Flatten a list exactly one level deep: items that are lists are spliced in
    positionally, anything deeper stays nested. Non-list items are kept verbatim.

    >>> flatten_once([[1, 2], [3, [4]], 5])
    [1, 2, 3, [4], 5]
    >>> flatten_once([])
    []
    >>> flatten_once(["ab", ["cd"]])
    ['ab', 'cd']
    """
    out = []
    for item in nested:
        if isinstance(item, list):
            out.extend(item)
        else:
            out.append(item)
    return out


def slugify(title):
    """Turn a title into a slug: lowercase, replace every run of characters that are
    not [a-z0-9] with a single '-', and strip leading/trailing dashes.

    >>> slugify("  Hello,  World! ")
    'hello-world'
    >>> slugify("A -- B/C")
    'a-b-c'
    >>> slugify("ABC 123")
    'abc-123'
    """
    lowered = title.lower()
    dashed = re.sub(r"[^a-z0-9]+", "-", lowered)
    return dashed.strip("-")


def mask_email(email):
    """Mask the local part of an address: keep its first character, replace the rest
    with exactly three asterisks, keep the '@' and the domain verbatim. The input
    always contains exactly one '@' and a non-empty local part.

    >>> mask_email("jdoe@example.com")
    'j***@example.com'
    >>> mask_email("a@b.co")
    'a***@b.co'
    """
    local, _, domain = email.partition("@")
    return f"{local[0]}***@{domain}"
