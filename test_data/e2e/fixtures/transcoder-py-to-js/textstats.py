"""Small text and number helpers used by the report generator."""

PUNCTUATION = ".,;:!?\"'()"


def word_freq(text, top=None):
    """Case-insensitive word counts, most frequent first, ties broken alphabetically."""
    counts = {}
    for raw in text.lower().split():
        word = raw.strip(PUNCTUATION)
        if word:
            counts[word] = counts.get(word, 0) + 1
    items = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))
    if top is not None:
        items = items[:top]
    return [[word, n] for word, n in items]


def median(nums):
    if not nums:
        raise ValueError("median of empty list")
    ordered = sorted(nums)
    mid = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[mid]
    return (ordered[mid - 1] + ordered[mid]) / 2


def round_half_even(x):
    """Round to the nearest integer, halves go to the even neighbour."""
    return round(x)


def wrap(text, width):
    """Greedy word wrap. A word longer than width gets a line of its own."""
    lines = []
    current = ""
    for word in text.split():
        if not current:
            current = word
        elif len(current) + 1 + len(word) <= width:
            current += " " + word
        else:
            lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def chunk(seq, size):
    """Split a list into consecutive pieces of at most `size` items."""
    if size <= 0:
        raise ValueError("size must be positive")
    return [seq[i:i + size] for i in range(0, len(seq), size)]


def title_case(text):
    """Upper-case every letter that follows a non-letter, lower-case the others."""
    return text.title()


def ring_index(i, n):
    """Index of position i on a ring of n slots, for any integer i."""
    return i % n


def half_of(x):
    """Integer half, rounded towards negative infinity."""
    return x // 2
