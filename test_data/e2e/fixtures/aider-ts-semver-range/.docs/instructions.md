# Instructions

Implement `satisfies(version, range)` in `src/semver.ts`. It tells whether a version string falls inside a version range expression.

## Versions

A version is `MAJOR.MINOR.PATCH` with optional `-prerelease` and optional `+build`, for example `1.2.3`, `1.2.3-beta.2`, `1.2.3+exp.5`. The three numbers are non-negative integers without leading zeros. A prerelease is dot-separated identifiers of `[0-9A-Za-z-]`; a numeric identifier has no leading zeros. Build metadata is ignored everywhere. Surrounding whitespace is ignored. Anything else (`1.2`, `v1.2.3`, `1.2.3.4`) is not a version and makes `satisfies` throw `TypeError`.

Precedence: compare major, minor, patch as numbers. A version with a prerelease is lower than the same version without one. Two prereleases are compared identifier by identifier from the left: numeric identifiers as numbers, other identifiers in plain string order, a numeric identifier is lower than a non-numeric one, and when all shared identifiers are equal the one with fewer identifiers is lower.

## Ranges

A range is one or more sets separated by `||`; a version satisfies the range when it satisfies at least one set. An empty set (or an empty range) matches any version that is not a prerelease. A set is either a hyphen range or a list of whitespace-separated comparators that must all hold. A range that cannot be parsed makes `satisfies` throw `TypeError`, even if an earlier set already matches.

Comparators are `<`, `<=`, `>`, `>=`, `=` or nothing (exact), optionally followed by whitespace, then a version that may be partial (`1`, `1.2`) and may use `x`, `X` or `*` for a missing part (`1.x`, `1.2.*`, `*`). A partial version carries a prerelease only when all three numbers are given.

### Partial versions

- Exact or `=`: `1.2.3` is that version; `1.2` or `1.2.x` is `>=1.2.0 <1.3.0`; `1` or `1.x` is `>=1.0.0 <2.0.0`; `*` or `x` is anything.
- `>`: `>1.2` is `>=1.3.0`; `>1` is `>=2.0.0`.
- `>=`: `>=1.2` is `>=1.2.0`; `>=1` is `>=1.0.0`.
- `<`: `<1.2` is `<1.2.0`; `<1` is `<1.0.0`.
- `<=`: `<=1.2` is `<1.3.0`; `<=1` is `<2.0.0`.
- `>*` and `<*` match nothing; `>=*` and `<=*` match everything.

### Caret `^`

The caret allows changes that do not modify the left-most non-zero number:

- `^1.2.3` is `>=1.2.3 <2.0.0`; `^1.2` and `^1.x` are `>=1.2.0 <2.0.0` and `>=1.0.0 <2.0.0`.
- `^0.2.3` is `>=0.2.3 <0.3.0`; `^0.0.3` is `>=0.0.3 <0.0.4`.
- `^0.0` is `>=0.0.0 <0.1.0`; `^0` and `^0.x` are `>=0.0.0 <1.0.0`.
- A prerelease on the lower bound is kept: `^1.2.3-beta.2` is `>=1.2.3-beta.2 <2.0.0`.

### Tilde `~`

- `~1.2.3` is `>=1.2.3 <1.3.0`; `~1.2` is `>=1.2.0 <1.3.0`; `~1` is `>=1.0.0 <2.0.0`; `~0` is `>=0.0.0 <1.0.0`.

### Hyphen ranges

`A - B` (spaces around the hyphen are required) means `>=A <=B`, with partial ends filled in: a partial lower end is padded with zeros (`1.2 - 2.3.4` is `>=1.2.0 <=2.3.4`), a partial upper end allows the whole partial (`1.2.3 - 2.3` is `>=1.2.3 <2.4.0`, `1.2.3 - 2` is `>=1.2.3 <3.0.0`).

## Prereleases

A version that has a prerelease satisfies a set only when every comparator of the set holds for it AND at least one comparator of that same set has a prerelease on the very same `major.minor.patch`. This is decided per set, after the ranges above have been expanded. So `1.2.3-beta.4` satisfies `^1.2.3-beta.2`, but `1.2.4-beta.1` does not, `1.2.3-beta.4` does not satisfy `>=1.0.0`, and `2.0.0-rc.1` does not satisfy `<2.0.0`.
