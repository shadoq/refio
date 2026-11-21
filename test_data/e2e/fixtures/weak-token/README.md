# Engagement notes

The target authenticates service-to-service calls with "Proto-Tokens" (see
`public/token-format.md`). We sniffed a few live tokens off the wire (`capture/issued-tokens.txt`)
and need to know whether the scheme survives an attacker who can observe traffic.

`tokens.py` is the auth library as deployed (it backs the verifier); `check.py` is the
exercise gate. The deliverable is a forged admin token that the verifier accepts.
