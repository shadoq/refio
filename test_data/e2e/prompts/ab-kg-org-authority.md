An org chart is stored as a small knowledge graph (`graph/org.triples`: `reports_to`
edges and `holds_role` ranks). The approval rules are fixed in `docs/authority.md`: two
request types allow self-approval when the requester's rank is sufficient, the two
sensitive types never do; anything above the requester's rank is resolved by walking up
the `reports_to` chain to the nearest sufficient rank.

Your task: resolve all ten questions from `docs/authority.md` into
`answers/authority.json` (flat object, key = question key, value = the approver's name).
`python3 checks.py` must exit 0 - it recomputes every resolution from the graph and the
rules. Do not modify the graph, the rules or the gate.
