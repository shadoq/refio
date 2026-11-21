# Approval authority rules and questions (authoritative)

Request types and the rank they require:

| request type        | required rank |
|---------------------|---------------|
| expense_report      | lead          |
| purchase_order      | manager       |
| security_exception  | director      |
| budget_transfer     | director      |

Approval resolution for requester R and type T:

- `expense_report` and `purchase_order`: the approver is **R herself** when R's rank
  meets the requirement (self-approval is allowed for these two types); otherwise the
  nearest person above R in the `reports_to` chain whose rank meets the requirement.
- `security_exception` and `budget_transfer`: self-approval is **never** allowed - the
  approver is the nearest person strictly above R in the chain whose rank meets the
  requirement.

Answer all ten questions into `answers/authority.json` (flat object, key = question key,
value = the approver's name as a string):

- `q1` - expense_report requested by elena
- `q2` - expense_report requested by dave
- `q3` - purchase_order requested by elena
- `q4` - purchase_order requested by carla
- `q5` - security_exception requested by farid
- `q6` - budget_transfer requested by gita
- `q7` - budget_transfer requested by ivan
- `q8` - security_exception requested by henk
- `q9` - expense_report requested by june
- `q10` - purchase_order requested by bob

`python3 checks.py` must exit 0 (it recomputes every resolution from the graph and the
rules above). Do not modify the graph, the rules or the gate.
