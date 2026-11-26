# Crypto: RSA with a low public exponent

A sloppy key-management rollout left three kinds of material on a captured
host, all using RSA with the public exponent e = 3 (see `docs/format.md`
for the message padding format):

1. `artifacts/capture/pub.key` holds a public key (JSON: `n`, `e`) and
   `artifacts/capture/c1.bin` the raw big-endian ciphertext of a padded
   secret. The padding bytes were drawn once; the padded block was
   encrypted as-is (textbook RSA).
2. `artifacts/broadcast/` holds the same (differently padded) secret sent
   under three different public keys: `pub1.json`, `pub2.json`, `pub3.json`
   with `ct1.bin`, `ct2.bin`, `ct3.bin`.
3. `artifacts/shared/pub4.json` is a fourth public key whose modulus
   shares exactly one prime factor with the modulus in `pub.key`.

Questions:

- `direct`: the secret string under `capture/`.
- `broadcast`: the secret string under `broadcast/`.
- `shared_prime`: the prime that `pub.key` and `pub4.json` have in
  common, as a decimal integer.

Write your findings to `solution/answers.json` in exactly this shape:

```json
{
  "task_name": "crypto-rsa-low-exponent",
  "answers": {
    "direct": "...",
    "broadcast": "...",
    "shared_prime": "..."
  }
}
```

`python3 check.py` (run from the task root) must exit 0. It re-derives
all three answers from the artifacts themselves and compares digests, so
guessing fails. Plain python3 is enough; no third-party packages are
installed. Do not modify `check.py`, `task.md`, `metadata.json`, `docs/`
or `artifacts/`.
