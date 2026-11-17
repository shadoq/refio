# Entity graph audit

`graph/entities.triples` is a knowledge graph, one triple per line:

    <subject> <predicate> <object>

Predicates:

- `born_year` / `died_year` - subject's birth/death year (a `born_year` marks the subject
  as an entity; anything referenced as a reports_to target must have one).
- `reports_to` - subject reports to object (must be acyclic, target must exist).
- `same_as` - subject and object are the same entity (must be symmetric).
- `parent_of` - subject is a parent of object (parents must be strictly older).

The five consistency rules, the rule identifiers and the subject conventions are fixed
in `docs/rules.md`. The graph contains exactly seven rule violations; the audit must
find them all - no more, no less.
