"""A small template engine."""

import re

from lookup import resolve


class TemplateError(ValueError):
    """Raised for malformed templates and for values that cannot be found."""


_TOKEN = re.compile(r"\\\{\{|\{\{(.*?)\}\}", re.DOTALL)


def _parse(template):
    root = []
    stack = [root]
    open_paths = []
    pos = 0

    def text(chunk):
        if "{{" in chunk:
            raise TemplateError("unclosed '{{'")
        if chunk:
            stack[-1].append(("text", chunk))

    for match in _TOKEN.finditer(template):
        text(template[pos:match.start()])
        pos = match.end()
        if match.group(1) is None:
            stack[-1].append(("text", "{{"))
            continue
        tag = match.group(1).strip()
        if "{{" in tag:
            raise TemplateError("tag contains '{{'")
        if not tag:
            raise TemplateError("empty tag")
        if tag == "/if":
            if not open_paths:
                raise TemplateError("{{/if}} without {{#if}}")
            open_paths.pop()
            stack.pop()
        elif tag == "#if" or tag.startswith("#if "):
            path = tag[3:].strip()
            if not path:
                raise TemplateError("{{#if}} needs a path")
            body = []
            stack[-1].append(("if", path, body))
            stack.append(body)
            open_paths.append(path)
        else:
            stack[-1].append(("var", tag))
    text(template[pos:])
    if open_paths:
        raise TemplateError("{{#if %s}} is never closed" % open_paths[-1])
    return root


def _render(nodes, context, out):
    for node in nodes:
        kind = node[0]
        if kind == "text":
            out.append(node[1])
        elif kind == "var":
            try:
                value = resolve(context, node[1])
            except KeyError:
                raise TemplateError("missing value: %s" % node[1]) from None
            out.append("" if value is None else str(value))
        else:
            try:
                truthy = bool(resolve(context, node[1]))
            except KeyError:
                truthy = False
            if truthy:
                _render(node[2], context, out)


def render(template, context):
    """Render the template with the given context."""
    out = []
    _render(_parse(template), context, out)
    return "".join(out)
