"""slugkit - turn text into URL slugs."""

import re
import unicodedata

_NOT_ALNUM = re.compile(r"[^a-z0-9]+")


def slugify(text, max_length=None, separator="-"):
    text = unicodedata.normalize("NFKD", text)
    text = text.encode("ascii", "ignore").decode("ascii").lower()
    text = _NOT_ALNUM.sub(separator, text).strip(separator)
    if max_length is not None:
        text = text[:max_length].rstrip(separator)
    return text
