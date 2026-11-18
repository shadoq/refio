"""Catalog URL paths."""

from app.slugs import make_slug


def product_path(category, name):
    return "/shop/%s/%s" % (make_slug(category), make_slug(name, max_length=40))
