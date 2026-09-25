# Release workspace

This workspace publishes one release of three packages to a local registry and exports the release document. Everything works offline; `packctl` never uses the network.

| Path | What |
|---|---|
| `manifests/release.json` | The release: every package, its version, artifact, SHA-256 checksum and dependencies. |
| `artifacts/` | The package artifacts listed in the manifest. |
| `.state/registry.json` | Registry state written by `packctl`. Do not edit it by hand. |
| `bin/packctl.cjs` | The registry tool. |
| `out/` | Exported release documents. |

## packctl

```
node bin/packctl.cjs --help
node bin/packctl.cjs reset
node bin/packctl.cjs verify
node bin/packctl.cjs publish <name>
node bin/packctl.cjs export <path>
```

- `reset` empties the registry for the release named in the manifest. Use it whenever the registry holds a stale or wrong entry: published entries cannot be replaced one by one.
- `publish <name>` checks the artifact of `<name>` against the manifest checksum and appends it to the registry. Every package listed in its `dependsOn` must already be published at its manifest version, so dependencies go first.
- `verify` recomputes every artifact checksum and checks that the registry holds each manifest package exactly once, at its manifest version and checksum, with dependencies before dependents.
- `export <path>` writes the registry as a release document (packages in publish order). It exports whatever the registry holds, so run `verify` first.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success. |
| 1 | Usage error or unknown command. |
| 2 | Unknown package name. |
| 3 | The registry does not match the manifest (stale, missing, duplicated or out-of-order entry). |
| 4 | An artifact is missing or its checksum does not match the manifest. |
| 5 | A dependency is not published at its manifest version. |
| 6 | The package is already published; run `reset` to rebuild the registry. |

## Publishing a release

1. `verify` to see the state of artifacts and registry.
2. `reset` if the registry is stale.
3. `publish` each package, dependencies first.
4. `verify` again; it must exit 0.
5. `export out/release.json`.
