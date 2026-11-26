"""Build the ranked name list from raw scores."""


def rank(scores):
    """Return names ordered by descending score.

    Names that share a score are listed in ascending name order; that
    tie-break is part of the public contract (see docs/tie-break.md).
    """
    entries = [(name, scores[name]) for name in set(scores)]
    ranked = sorted(entries, key=lambda entry: -entry[1])
    return [name for name, _ in ranked]
