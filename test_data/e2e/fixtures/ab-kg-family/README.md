# Family graph

`graph/family.triples` is a knowledge graph, one triple per line:

    <subject> <predicate> <object>

Predicates used here:

- `parent_of` - subject is a parent of the object (a child may have two parents).
- `spouse_of` - symmetric: A spouse_of B implies B spouse_of A (both directions are
  written out in the file).

Nobody marries within the tree; spouses share their children in every case. Names are
unique and lowercase. See `docs/questions.md` for the kinship definitions the answers
must use.
