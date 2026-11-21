The Orders API is being upgraded from v1 to v2. In this project you have:

- `api/openapi-v1.json` - the spec the current SDK was generated against.
- `api/openapi-v2.json` - the spec the server team is about to ship.
- `client/sdk.py` - the generated SDK (v1-based) that this project still runs in production.
- `README.md` - context.

Your task: analyse the v1 -> v2 diff **from the shipped SDK's perspective** and write
`report/breaking-changes.md` that:

- lists every **breaking** change (something that would make the current SDK or its callers
  misbehave against a v2 server), each with:
  - the endpoint and field/parameter involved,
  - what changed (removed / now required / type changed),
  - the exact place in `client/sdk.py` that breaks (function and what it does wrong),
- explicitly lists the changes that are **not breaking** and why.

Do not modify the specs, the SDK or any other project file - the deliverable is the report
only.
