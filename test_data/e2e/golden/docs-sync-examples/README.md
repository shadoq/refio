# tallykit

A tiny library for counting things. All examples below are executed by `python checks.py`.

## Creating and counting

Build a tally from any iterable and keep counting with `add`. `total` is a property.

```python
from tallykit import Tally

t = Tally("abracadabra")
t.add("z", 3)
print(t.total)
print(t.count("a"))
```

```text
14
5
```

## Most common items

Ties are ordered by the text of the item.

```python
from tallykit import Tally

t = Tally("abracadabra")
print(t.most_common(2))
```

```text
[('a', 5), ('b', 2)]
```

## Ratios

The ratio of an item is rounded to 2 digits by default; pass `ndigits` to change it.

```python
from tallykit import Tally

t = Tally("aab")
print(t.ratio("a"))
print(t.ratio("a", ndigits=4))
```

```text
0.67
0.6667
```

## Merging

`merge` returns a new tally and leaves both inputs untouched.

```python
from tallykit import Tally

a = Tally("aab")
b = Tally("bcc")
both = a.merge(b)
print(both.total, a.total)
print(both.most_common())
```

```text
6 3
[('a', 2), ('b', 2), ('c', 2)]
```

## Parsing counts

`parse_counts` returns a `Tally`.

```python
from tallykit import parse_counts

t = parse_counts("apple=2, pear=5")
print(t.most_common())
print(type(t).__name__)
```

```text
[('pear', 5), ('apple', 2)]
Tally
```

## Errors

Counts must be positive.

```python
from tallykit import Tally

try:
    Tally().add("x", 0)
except ValueError as exc:
    print(exc)
```

```text
n must be positive
```

## Size and membership

`len` is the number of distinct items.

```python
from tallykit import Tally

t = Tally("aab")
print(len(t), "a" in t, "q" in t)
```

```text
2 True False
```
