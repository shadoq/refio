"""Slugs for product and category URLs."""

from vendor.slugkit import slugify


def make_slug(title, max_length=None):
    return slugify(title, max_length=max_length)
