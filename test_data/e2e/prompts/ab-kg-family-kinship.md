A partner team exported their family tree as a small knowledge graph
(`graph/family.triples`, predicates: `parent_of`, symmetric `spouse_of`). The kinship
definitions you must use are fixed in `docs/questions.md` - siblings exclude self,
aunts/uncles are parents' siblings, cousins are aunts'/uncles' children, lists are
sorted alphabetically.

Your task: answer all ten questions into `answers/kinship.json` (flat JSON object, key =
question key, value = sorted array of names; `q8_spouse_of_dan` is a single string).
`python3 checks.py` must exit 0 - it recomputes every answer from the triples and
compares. Do not modify the graph, the questions or the gate.
