# Consistency rules (authoritative)

The audit checks five rules. Rule identifiers and the subject convention for the
violation entries are fixed:

| rule id              | definition                                                                 | violation entry                       |
|----------------------|-----------------------------------------------------------------------------|----------------------------------------|
| `cycle`              | the `reports_to` relation must be acyclic                                   | one entry per cycle: `subject` = the alphabetically smallest member of the cycle |
| `born_after_death`   | when both years exist, `born_year` must be strictly earlier than `died_year` (equal years violate) | `subject` = the person                 |
| `same_as_asymmetric` | every `A same_as B` requires `B same_as A`                                  | `subject` = the A whose counterpart is missing |
| `missing_manager`    | every `reports_to` target must exist as an entity (have a `born_year`)       | `subject` = the missing target         |
| `parent_not_older`   | every `parent_of` parent must have a `born_year` strictly earlier than the child's (equal years violate) | `subject` = the parent                 |

Write `audit/violations.json`:

```json
{
  "violations": [
    { "rule": "<rule id>", "subject": "<name>" }
  ]
}
```

The audit must contain exactly the seven violations the graph has - no more, no less
(the gate recomputes the full set and compares it as a set, duplicates included).
`python3 checks.py` must exit 0. Do not modify the graph, the rules or the gate.
