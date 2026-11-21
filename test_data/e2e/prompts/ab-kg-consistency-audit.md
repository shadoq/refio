A partner sent an entity graph (`graph/entities.triples`: `born_year`/`died_year`,
`reports_to`, `same_as`, `parent_of`) and an audit contract (`docs/rules.md`: five rules,
fixed rule ids and subject conventions). The graph hides exactly **seven** violations -
a manager cycle, two impossible life spans, an asymmetric `same_as`, a reports_to target
that does not exist as an entity, and two parents not older than their children.

Your task: audit the graph and write `audit/violations.json` - a JSON object with a
`violations` array of `{"rule": ..., "subject": ...}` entries covering exactly the real
violations (no more, no less, no duplicates). `python3 checks.py` must exit 0 - it
recomputes the full set from the graph and compares. Do not modify the graph, the rules
or the gate.
