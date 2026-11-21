"""A small template engine."""


class TemplateError(ValueError):
    """Raised for malformed templates and for values that cannot be found."""


def render(template, context):
    """Render the template with the given context."""
    pass
