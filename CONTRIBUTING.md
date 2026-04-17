# Contributing to RefIo

Thanks for your interest in RefIo. This is an early-stage open-source project and
contributions — code, docs, bug reports, design discussion — are genuinely welcome.

## Before you start

- Read the [Roadmap](docs/ROADMAP.md) to see where the project is heading and which
  areas are open for contribution.
- For non-trivial changes, open an issue first to align on approach before writing
  code. Small fixes (typos, obvious bugs) can go straight to a PR.
- By contributing you agree your work is licensed under the project's [MIT license](LICENSE).

## Development setup

Requirements: JDK 17, Git. IntelliJ IDEA (Community or Ultimate) recommended.

```bash
git clone https://github.com/shadoq/refio.git
cd refio

# Launch sandbox IDE with the plugin
./gradlew :intellij-plugin:runIde

# Build plugin ZIP
./gradlew :intellij-plugin:buildPlugin

# Build CLI
./gradlew :cli:installDist
./cli/build/install/cli/bin/cli --project /path/to/project --mode AGENT
```

See [CLAUDE.md](CLAUDE.md) for a full overview of build commands, module layout,
and architectural patterns.

## Module layout

Three Gradle modules:

- **`:core`** — IDE-independent logic (LLM clients, tools, RAG, agents, DB). No
  IntelliJ Platform dependency. New files here must stay IDE-agnostic.
- **`:intellij-plugin`** — IntelliJ plugin UI and services. Depends on `:core`.
- **`:cli`** — Standalone terminal TUI. Depends on `:core`.

All target JDK 17.

## Coding guidelines

- Kotlin, 4-space indent, no wildcard imports.
- Files stay in the 200–300 LOC range where practical. Split when they grow.
- Use `dualLogger()` for logging — never raw `println`.
- Never log secrets. The `detectSensitiveLogging` Gradle task fails the build if
  API key patterns appear in log statements.
- Prefer editing existing files over creating new ones. Don't introduce abstractions
  beyond what the task requires.
- Write comments only when the *why* is non-obvious. Don't describe what the code does.
- Security matters: all file ops must go through `PathSandbox`; terminal commands
  through `CommandRule`.

## Testing

- JUnit 5 + MockK + Turbine. Tests mirror source structure under `src/test/kotlin/`.
- `:core` has a Jacoco coverage gate (35% instructions). Don't regress it.

```bash
./gradlew test                                # all modules
./gradlew :core:test                          # core only
./gradlew :core:jacocoTestCoverageVerification
./gradlew :intellij-plugin:check              # includes detectSensitiveLogging
```

## Quality checks before opening a PR

```bash
./gradlew detekt ktlintCheck
./gradlew :core:check :intellij-plugin:check
```

## Commit and PR conventions

- Commit messages: short imperative subject (`fix: resolve path sandbox symlink edge case`).
  Prefixes `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:` are welcome but
  not required.
- Keep PRs focused — one logical change per PR. Split unrelated work.
- PR description should explain the *why*, not just the *what*. Link the issue if one exists.
- For UI changes, include a screenshot or short note on how you verified the change in
  a running IDE / TUI.

## Areas where help is especially welcome

From the roadmap:

- Plan refinement loop (Phase 4)
- Agent dashboard UI (Phase 5) — Swing components + TUI views
- Git worktree isolation (Phase 3) — `GitService` design
- Multi-agent runtime follow-up ([docs/0054-multiagent.md](docs/0054-multiagent.md))
- Provider adapters for new LLMs
- MCP preset configurations
- Documentation and onboarding guides
- Extending test coverage on `:core`

## Reporting bugs

Open an issue at [github.com/shadoq/refio/issues](https://github.com/shadoq/refio/issues)
with:

- RefIo version, IntelliJ version (or CLI), OS
- Steps to reproduce
- Relevant log excerpt (redact any secrets first)
- Expected vs actual behavior

## Communication

- **Issues** — bugs, feature requests, design discussions
- **Pull requests** — code, docs, fixes

No Discord, no Slack. Keep the discussion on GitHub so it's searchable and public.

---

Thanks for helping RefIo grow.
