"""Slugs for product and category URLs."""

from vendor.slugkit import slugify

_TRANSLITERATION = str.maketrans({"\u0142": "l", "\u0141": "L"})


def make_slug(title, max_length=None):
    return slugify(title.translate(_TRANSLITERATION), max_length=max_length)
