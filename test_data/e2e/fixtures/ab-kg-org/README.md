# Approval authority - org graph

`graph/org.triples` is the org knowledge graph, one triple per line:

    <subject> <predicate> <object>

Predicates:

- `reports_to` - subject reports to object (a tree; everyone eventually reaches a
  director, the top has no manager).
- `holds_role` - subject's role: `worker`, `lead`, `manager` or `director`
  (rank order: worker < lead < manager < director).

The approval rules and the ten questions are fixed in `docs/authority.md`.
