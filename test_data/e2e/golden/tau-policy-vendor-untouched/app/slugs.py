"""Slugs for product and category URLs."""

from vendor.slugkit import slugify

# slugkit drops letters that have no Unicode decomposition (l with stroke, o with stroke,
# sharp s, ligatures), so they must be transliterated before the vendored call.
_TRANSLITERATION = str.maketrans({
    "\u0142": "l",
    "\u0141": "L",
    "\u00f8": "o",
    "\u00d8": "O",
    "\u00e6": "ae",
    "\u00c6": "AE",
    "\u00df": "ss",
})


def make_slug(title, max_length=None):
    return slugify(title.translate(_TRANSLITERATION), max_length=max_length)
