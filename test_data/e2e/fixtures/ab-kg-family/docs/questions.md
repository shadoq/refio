# Kinship questions (authoritative definitions)

Answer all ten questions into `answers/kinship.json`: a flat JSON object, key = the
question key, value = a **JSON array of names sorted alphabetically** (or a single string
where marked). Definitions:

- **parents** of X: everyone related to X by `parent_of` (X child, S parent).
- **grandparents** of X: parents of X's parents.
- **siblings** of X: people who share at least one parent with X, **excluding X**.
- **aunts/uncles** of X: siblings of X's parents (excluding X's parents themselves).
- **cousins** of X: children of X's aunts/uncles (excluding X, X's siblings and X's
  parents).
- **spouse** of X: their `spouse_of` partner (single string, not an array).
- **children** of X: everyone X is a parent of.

Questions:

- `q1_parents_of_harry`
- `q2_grandparents_of_lara`
- `q3_siblings_of_irene`
- `q4_siblings_of_gina`
- `q5_aunts_uncles_of_mike`
- `q6_cousins_of_nina` (may legitimately be empty)
- `q7_cousins_of_lara`
- `q8_spouse_of_dan` (single string)
- `q9_children_of_alfred`
- `q10_grandparents_of_oscar`

`python3 checks.py` must exit 0 (it recomputes every answer from the triples and
compares). Do not modify the graph or the gate.
