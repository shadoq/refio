# Deobfuscated beacon configuration

The script builds its config from a rotated string array. The IIFE at the top applies
`push(shift())` 26 times (`while (--_0x4)` with `_0x4 = 0x1a + 1`), so the array's load-time
order is the literal rotated left by 26 (i.e. right by 4, for the 10-element array). Reading
the accessor `_0x7b('0x0'..'0x4')` against the rotated array yields:

| accessor | value |
|---|---|
| `0x0` | `cdn-metrics.example` |
| `0x1` | `/.well-known/pixel` |
| `0x2` | `9f8e7d` |
| `0x3` | `60` |
| `0x4` | `3` |

The remaining slots hold decoy strings (`fonts-cdn.example`, `/analytics.js`, `b1a2c3`,
`1440`, `17`) that the literal order would wrongly suggest.

Recovered runtime configuration:

- `c2`: `https://cdn-metrics.example/.well-known/pixel`
- `key`: `9f8e7d`
- `every`: `60000` ms (the literal `60` is parsed and multiplied by 1000)
- `rounds`: `3`

Verdict: the beacon reports to `cdn-metrics.example` every 60 seconds for 3 rounds,
authenticating with key `9f8e7d`. Nothing in the sample was modified.
