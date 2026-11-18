# Instructions

Build a small text template engine for notification e-mails. It has two modules: `lookup.py` finds values in nested data and `template.py` renders templates with it.

## `lookup.resolve(context, path)`

`path` is dot separated, for example `user.address.city` or `items.0.name`.

- A part is looked up as a key when the current value is a `dict`, and as a zero-based index when it is a `list` or `tuple` (the part must be made of digits).
- It returns the value found, whatever it is (`None`, `0`, `False` and `""` included).
- It raises `KeyError(path)` when any part is missing, an index is out of range, or the current value is not a container (for example a string or a number). An empty path, or an empty part such as in `a..b`, is also a `KeyError`.

## `template.render(template, context)`

Returns the rendered text. The following syntax is recognised, everything else is copied as it is (a lone `}}` is ordinary text).

- **Values**: `{{ path }}` is replaced by the value found with `lookup.resolve`. Whitespace (including line breaks) inside the braces is ignored. The value is written with `str()`, except `None`, which is written as an empty string. So `0` renders `0` and `False` renders `False`.
- **Missing values**: a path that cannot be resolved raises `TemplateError` whose message contains the path.
- **Conditionals**: `{{#if path}} ... {{/if}}` renders its body only when the value is truthy (Python rules). A path that cannot be resolved counts as false and is **not** an error. Conditionals can be nested and can contain values.
- **Escape**: `\{{` produces a literal `{{` and nothing after it is interpreted until the next tag: `\{{ name }}` renders as `{{ name }}`.
- **One pass only**: substituted values are plain text. A value that itself contains `{{ ... }}` is inserted unchanged and never expanded.

`TemplateError` (a subclass of `ValueError`, defined in `template.py`) is also raised for malformed templates: a `{{` that is never closed, a tag that contains another `{{`, an empty tag (`{{ }}`), `{{#if}}` without a path, `{{#if x}}` without `{{/if}}`, and `{{/if}}` without an open conditional.
