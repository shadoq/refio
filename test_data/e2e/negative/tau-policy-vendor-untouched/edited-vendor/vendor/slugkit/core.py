"""slugkit - turn text into URL slugs."""

import re
import unicodedata

_NOT_ALNUM = re.compile(r"[^a-z0-9]+")

_EXTRA = str.maketrans({
    "\u0142": "l", "\u0141": "L", "\u00f8": "o", "\u00d8": "O",
    "\u00e6": "ae", "\u00c6": "AE", "\u00df": "ss",
})


def slugify(text, max_length=None, separator="-"):
    text = text.translate(_EXTRA)
    text = unicodedata.normalize("NFKD", text)
    text = text.encode("ascii", "ignore").decode("ascii").lower()
    text = _NOT_ALNUM.sub(separator, text).strip(separator)
    if max_length is not None:
        text = text[:max_length].rstrip(separator)
    return text
