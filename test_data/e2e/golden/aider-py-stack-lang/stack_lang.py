"""A tiny stack language with user-defined words."""

import re

_NUMBER = re.compile(r"-?\d+")


class StackLangError(Exception):
    """Raised for every error of the stack language."""


def _pop(stack):
    if not stack:
        raise StackLangError("stack underflow")
    return stack.pop()


def _binary(fn):
    def apply(stack):
        b = _pop(stack)
        a = _pop(stack)
        stack.append(fn(a, b))

    return apply


def _div(a, b):
    if b == 0:
        raise StackLangError("divide by zero")
    q = abs(a) // abs(b)
    return q if (a < 0) == (b < 0) else -q


def _dup(stack):
    a = _pop(stack)
    stack.extend([a, a])


def _drop(stack):
    _pop(stack)


def _swap(stack):
    b = _pop(stack)
    a = _pop(stack)
    stack.extend([b, a])


def _over(stack):
    b = _pop(stack)
    a = _pop(stack)
    stack.extend([a, b, a])


_PRIMITIVES = {
    "+": _binary(lambda a, b: a + b),
    "-": _binary(lambda a, b: a - b),
    "*": _binary(lambda a, b: a * b),
    "/": _binary(_div),
    "dup": _dup,
    "drop": _drop,
    "swap": _swap,
    "over": _over,
}


class StackMachine:
    """Stack of integers plus a table of words; both survive between runs."""

    def __init__(self):
        self._stack = []
        # A word is a flat tuple of operations: an int pushes itself, a str names a primitive.
        self._words = {name: (name,) for name in _PRIMITIVES}

    @property
    def stack(self):
        """Return a copy of the stack, bottom first."""
        return list(self._stack)

    def run(self, source):
        """Execute the source text and return a copy of the stack, bottom first."""
        saved_stack = list(self._stack)
        saved_words = dict(self._words)
        try:
            self._execute(source.split())
        except StackLangError:
            self._stack = saved_stack
            self._words = saved_words
            raise
        return self.stack

    def _execute(self, tokens):
        i = 0
        while i < len(tokens):
            token = tokens[i].lower()
            i += 1
            if token == ":":
                i = self._define(tokens, i)
            elif token == ";":
                raise StackLangError("unexpected ;")
            else:
                self._call(self._resolve(token))

    def _resolve(self, token):
        if _NUMBER.fullmatch(token):
            return (int(token),)
        if token in self._words:
            return self._words[token]
        raise StackLangError(f"unknown word: {token}")

    def _define(self, tokens, i):
        if i >= len(tokens) or tokens[i] == ";":
            raise StackLangError("missing word name")
        name = tokens[i].lower()
        i += 1
        if _NUMBER.fullmatch(name):
            raise StackLangError(f"cannot define number: {tokens[i - 1]}")
        body = []
        while True:
            if i >= len(tokens):
                raise StackLangError("unterminated definition")
            token = tokens[i].lower()
            i += 1
            if token == ";":
                break
            if token == ":":
                raise StackLangError("nested definition")
            body.extend(self._resolve(token))
        self._words[name] = tuple(body)
        return i

    def _call(self, ops):
        for op in ops:
            if isinstance(op, int):
                self._stack.append(op)
            else:
                _PRIMITIVES[op](self._stack)
