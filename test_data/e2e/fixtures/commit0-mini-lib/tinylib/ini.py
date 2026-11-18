"""INI-like configuration parsing.

Syntax:

* ``[name]`` starts a section (surrounding spaces in the name are stripped; an
  empty name is an error). Keys that appear before the first section belong to
  the section named ``""``, which only exists in the result when it has keys.
* ``key = value`` or ``key: value``. The key ends at the first ``=`` or ``:``;
  key and value are stripped. A repeated key in a section keeps the last value, and
  a repeated section merges into the earlier one.
* Lines whose first non-blank character is ``#`` or ``;`` are comments. An inline
  comment starts at ``#`` or ``;`` that is preceded by whitespace.
* A value in double quotes keeps everything between the quotes exactly (spaces,
  ``;`` and ``#`` included). Only a comment may follow the closing quote. There are
  no escape sequences.
* A line that starts with whitespace and follows a key line (comment lines in
  between are ignored, a blank line ends the continuation) continues that value.
  The continuation lines are stripped and joined to the value with ``\\n``; when the
  value so far is empty the first continuation line becomes the value without a
  leading newline.
* Any other line is an error.

Interpolation (on by default): ``${key}`` in a value is replaced by the (already
interpolated) value of that key in the same section, ``${section:key}`` by the
value of a key in another section, and ``$${`` is a literal ``${``. A reference to
something that does not exist raises KeyError with the reference text. A circular
reference raises ValueError.

All errors about a line raise ValueError whose message starts with
``line N`` (N counted from 1).
"""


def parse(text, interpolate=True):
    """Parse text into a dict mapping section name to a dict of key to value."""
    raise NotImplementedError
