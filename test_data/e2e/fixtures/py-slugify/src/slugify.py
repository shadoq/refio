import re


def slugify(title):
    """Converts a human title to a lowercase URL slug.

    Non-alphanumeric runs become single dashes; the slug never starts or
    ends with a dash.
    """
    slug = re.sub(r"[^A-Za-z0-9]+", "-", title)
    return slug
