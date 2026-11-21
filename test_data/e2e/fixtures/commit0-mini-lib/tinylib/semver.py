"""Semantic versions.

Version text looks like ``1.2.3``, optionally with a leading ``v``, a prerelease
after a hyphen (``1.2.3-alpha.1``) and build metadata after a plus sign
(``1.2.3+build5``). Build metadata is accepted and ignored everywhere.
"""
from collections import namedtuple

Version = namedtuple("Version", "major minor patch pre")


def parse(text):
    """Parse version text into Version(major, minor, patch, pre).

    major, minor and patch are ints. pre is a tuple of the dot separated
    prerelease identifiers (strings), empty for a release. Raises ValueError for
    anything that is not exactly three numeric parts (``1.2`` and ``1.2.3.4`` are
    invalid), including an empty prerelease.
    """
    raise NotImplementedError


def compare(a, b):
    """Compare two version texts by semantic version precedence.

    Returns -1, 0 or 1. Major, minor and patch compare numerically. A version with
    a prerelease is lower than the same version without one. Two prereleases
    compare identifier by identifier: numeric identifiers compare as numbers,
    numeric identifiers are lower than alphanumeric ones, alphanumeric identifiers
    compare as text, and when all shared identifiers are equal the one with fewer
    identifiers is lower. Build metadata does not matter.
    """
    raise NotImplementedError


def satisfies(version, spec):
    """Return True when the version text satisfies the range text spec.

    spec is a list of comparators separated by spaces and/or commas, and the
    version must satisfy all of them. A comparator is one of:

    * ``>=X.Y.Z``, ``>X.Y.Z``, ``<=X.Y.Z``, ``<X.Y.Z``, ``=X.Y.Z`` or a bare ``X.Y.Z``
      (exact match, by precedence)
    * ``^X.Y.Z`` - at least X.Y.Z and below the next change of the left-most
      non-zero part: ``^1.2.3`` is ``>=1.2.3 <2.0.0``, ``^0.2.3`` is
      ``>=0.2.3 <0.3.0`` and ``^0.0.3`` is ``>=0.0.3 <0.0.4``
    * ``~X.Y.Z`` - ``>=X.Y.Z <X.(Y+1).0``
    * ``*`` - anything

    A version that has a prerelease only satisfies a spec when at least one
    comparator names a prerelease of the same major.minor.patch (for example
    ``>=1.3.0-alpha`` accepts ``1.3.0-beta.1`` but ``>=1.0.0`` does not). An empty
    spec behaves like ``*``. Raises ValueError for an unknown comparator or an
    invalid version.
    """
    raise NotImplementedError


def max_satisfying(versions, spec):
    """Return the highest version text from versions that satisfies spec, or None."""
    raise NotImplementedError
