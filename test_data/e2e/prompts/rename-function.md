Rename the function `legacyFetch` to `fetchRecord` across the whole project: its definition in
`src/Api.kt` and every call site (`src/Client.kt`, `src/Worker.kt`). No reference to the old
name `legacyFetch` may remain anywhere. Do not change behaviour.
