"""Plain text tables.

``render(rows, headers=None, align=None)`` returns a string.

* Cells are converted with ``str()``; ``None`` becomes an empty cell. Rows may have
  different lengths: missing cells are empty. The table has as many columns as the
  longest of the rows and the headers.
* Every column is as wide as its widest cell (headers included).
* ``align`` is a string with one letter per column: ``l`` left, ``r`` right,
  ``c`` center. Missing letters mean ``l``; any other letter raises ValueError.
  When a centered cell leaves an odd number of spare characters the extra one goes
  to the right.
* Cells in a line are joined with `` | ``. When headers are given, the first line is
  the headers and the second line is a rule made of ``-`` for every column joined
  with ``-+-``.
* Trailing spaces are removed from every line, lines are joined with ``\\n`` and
  there is no trailing newline. No rows and no headers give an empty string.

Example::

    render([["pen", 3], ["notebook", 12]], headers=["item", "qty"], align="lr")

    item     | qty
    ---------+----
    pen      |   3
    notebook |  12
"""


def render(rows, headers=None, align=None):
    raise NotImplementedError
