# Ranking tie-break

The public `rank(scores)` contract: names are ordered by descending score and
names that share a score are listed in ascending name order (plain string
comparison). The same order is used inside `daily_report`. Any change to this
tie-break is a breaking change and must go through a deprecation cycle.
