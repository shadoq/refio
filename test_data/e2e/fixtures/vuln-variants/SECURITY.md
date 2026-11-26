# Security policy

Report suspected vulnerabilities to security@kellys-art.example. Please do not open a public
issue for a suspected injection or data exposure.

## Advisories

### SEC-ADV-2026-031 - SQL injection in offer lookup (fixed in 0.4.2)

- Severity: High (CVSS 8.8)
- Affected: `shop` < 0.4.2
- Reporter: external researcher, coordinated disclosure

`find_offer` in `shop/offers.py` built its statement with string formatting and spliced the
caller-supplied offer code into the SQL text. A crafted code such as `' OR '1'='1` returned
the first offer row for any input, and stacked and UNION payloads reached every table the
connection could read. The function now passes the code as a bound parameter.

The advisory was written from the reporter's proof of concept and covers only the function
that was reported. A sweep of the other query modules for the same pattern has not been done
yet; it is tracked as a separate task.
