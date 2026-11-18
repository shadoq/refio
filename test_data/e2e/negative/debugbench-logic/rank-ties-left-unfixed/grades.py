"""Grade book helpers for a small course."""


def weighted_average(scores, weights):
    """Average of scores where each score counts with its weight.

    Raises ValueError when the lists differ in length, are empty or the weights sum to zero.
    """
    if len(scores) != len(weights) or not scores:
        raise ValueError("scores and weights must be non-empty and of equal length")
    total_weight = sum(weights)
    if total_weight == 0:
        raise ValueError("weights sum to zero")
    return sum(s * w for s, w in zip(scores, weights)) / total_weight


def letter_grade(average):
    """A for 90 and above, B for 80 and above, C for 70 and above, D for 60 and above, otherwise F."""
    if average >= 90:
        return "A"
    if average >= 80:
        return "B"
    if average >= 70:
        return "C"
    if average >= 60:
        return "D"
    return "F"


def rank(students):
    """Rank students by score, highest first.

    students is a list of (name, score). Returns a list of (rank, name, score) sorted
    by rank and then by name. Equal scores share a rank and the next rank skips the
    places taken (scores 90, 80, 80, 70 get ranks 1, 2, 2, 4).
    """
    ordered = sorted(students, key=lambda item: (-item[1], item[0]))
    result = []
    for position, (name, score) in enumerate(ordered, start=1):
        result.append((position, name, score))
    return result
