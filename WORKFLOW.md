# WORKFLOW.md

How to work in this repository. What the project is, how to build and test it, and its architecture are in `AGENTS.md` - read it if you have not.

## Agent Rules

Apply to every task. Precedence: the user's explicit instruction > these rules.
Hard floor, overridable only by an explicit and knowing user decision in the moment: no secrets (12), confirm destructive/irreversible actions (2), no auto-commit (13).
Bias: caution over speed on non-trivial work.

Proportionality overrides every rule below. These rules set the ceiling of the process, not its obligatory minimum. When a rule would produce an artifact that reduces no real risk for the change at hand, skip it and say in one sentence that you did and why. This never relaxes the hard floor above.

### 1. Language and typography
- Talk to the user in Polish, plain, no marketing jargon.
- No jargon, no untranslated English terms, no acronyms the user did not use first ("seam", "edge case", "fixture", "happy path", "TDD-red"). Name the actual thing in Polish: not "brak seamu", but "nie da się podstawić atrapy klienta HTTP w tym miejscu".
- If a term genuinely has no Polish equivalent, use it once and explain it in the same sentence in a few words.
- Describe reality, concretely and briefly: what happened, where, what it means for the user. Short sentences over dense ones; no compressed shorthand that saves you a line and costs the reader a question.
- Code identifiers, comments, internal logs: English.
- User-facing text (UI, API responses, errors): follow the project's existing language and i18n mechanism; English if there is none.
- Working docs may stay Polish.
- Never use `—` or `–` anywhere. Plain hyphen `-`.
- Markdown: one paragraph is one line. Never hard-wrap prose to 80/100 columns; let the editor wrap.
- Diagrams (docs, comments, plans): ASCII/text only. No Mermaid, no images, no external diagram tools.

### 2. Think, read, decide
- State assumptions. Push back when something simpler works.
- Before writing code, read the exports, the immediate callers and the shared utilities you touch.
- If you cannot tell why existing code looks the way it does, ask.
- Do not trust memory for library or framework APIs. Check the version pinned in the project and read its docs or changelog before writing code against it; heed deprecation notices.
- Ask based on the decision, not on your discomfort: irreversible, costly or contract-changing → ask; otherwise record the assumption and go.
- Low-risk, reversible → proceed.
- Medium-risk (shared code or behavior) → proceed with a short inline plan.
- High-risk (public API, schema, data model, security, billing, migration, architecture, irreversible state) → stop and ask first, unless the user already approved this concrete change.
- Approval must be explicit. A review comment, a question or silence is not approval.
- Stop only when genuinely blocked, not at the first uncertainty.

### 3. Simplicity first
- Minimum code that solves the problem. Nothing speculative.
- No abstraction for single-use code.
- Prefer the minimum viable change, unless it adds maintenance risk in a known hotspot.

### 4. Surgical changes
- Touch only what you must. Do not improve adjacent code.
- Match existing style. Do not refactor what is not broken.
- Root cause outside scope → report it, do not patch symptoms.
- If a formatter or codegen rewrites a whole file, keep only your diff and revert the churn.
- Repo-wide formatting is a separate work item, never a side effect.

### 5. Goal-driven execution
- Define success criteria first, then loop until verified.
- "Done" scales to the change: code plus what it actually needs (tests, user docs, migration, error handling). Compiling is not done.

### 6. Model only for judgment calls
- Model for: classification, drafting, summarization, extraction, genuinely ambiguous intent.
- Never for deterministic work (routing, retries, fixed transforms) when plain code does the job.

### 7. Token budgets are not advisory
- Rough guide: small 10k, medium 30k, large 90k; session cap comes from the user or config.
- At a task budget → write a checkpoint (rule 10). At the session cap → hand off working state to a fresh session, do not just truncate.
- Surface the breach, never overrun silently.

### 8. Tests: TDD by default, real flows, not trivia
- TDD is the default for behavioral code: write the failing test that defines success, then the minimum code, then refactor.
- Test complex or risky flows: business logic, state machines, guardrails, parsers, edge cases, regressions.
- Skip TDD (and usually the test itself) for simple, non-behavioral changes: getters, renames, config or version bumps, docs, styling, a button color, a changed label, a passthrough wrapper, integrations genuinely hard to isolate.
- Small change, especially in the GUI (layout, wording, colors, wiring an existing call to a new button) → no unit test. Tests are for logic worth protecting, not for proving that a widget was added.
- Outside those exemptions a change in behavior needs at least one regression test that fails without the fix; "not practical" must name a concrete reason, it is not a blanket excuse.
- Test name and inputs express the business rule, so the reader sees WHY it matters.
- A test that cannot fail when the business logic changes is dead weight; delete it instead of writing it.
- Comment only the non-obvious.

### 9. Match the approach to the work
- Feature → failing test first (rule 8), minimum code, then refactor.
- Bugfix → reproduce with a failing test, root-cause, fix, then refactor.
- Exploration → read-only explorer; keep the conclusion, not file dumps.
- Planning or design → plan first.
- Review → adversarial review before merge.
- Cleanup → quality only, no behavior change.
- Describe agents by role (read-only explorer, adversarial reviewer), never by hardcoded agent name.

### 10. Checkpoint at phase changes
- Checkpoint on phase change (analysis → implementation → tests → blocked), not per file or command.
- A checkpoint says: what was done, what is verified, what is left.
- Do not continue from a state you cannot describe back.

### 11. Fail loud and do not average conflicts
- "Completed" is false if anything was skipped silently.
- When reporting tests: name which scopes ran, which were skipped and why, plus the residual risk. Skipping e2e or paid integrations is fine, hiding it is not.
- Every acceptance criterion gets a verdict: pass, fail, or not testable (with the reason). Never mark a criterion as passed without having exercised it.
- The final report lists what was deliberately left out of scope, not only what was done.
- Two contradicting patterns → first check whether the difference is intentional; if no reason is found, pick one (local to the change, more recent or better tested), say why, flag the other for cleanup.
- Conformance to the codebase beats your taste. If a convention is harmful, surface it, do not fork silently.

### 12. Secrets and production data
- Never print, log or commit tokens, keys, passwords, connection strings, `.env` or credential files.
- Mask sensitive values in logs, fixtures, test data and examples.
- Need a secret? Read it from the project's existing config mechanism. Never hardcode or invent one.
- No real personal data in tests, fixtures or seed data. Never copy production rows into the repo, not even "just one to reproduce it" - generate synthetic values of the same shape.
- Tests and local runs point at local/test storage and stubs. They must never mutate a production database or a live external service, and never call a paid API without the user approving that exact call.
- Reading production data for diagnosis needs the user to approve the concrete query first. Report an aggregate or a masked sample, never raw rows in chat, docs or code.

### 13. Commits and branches
- Do NOT commit automatically. Commit only when the user asks.
- Message: one short imperative sentence, no body. What changed, not how hard it was.
- No trailers, no `Co-Authored-By`, no "Generated with Claude Code / Codex / any agent", no assistant attribution of any kind.
- Rationale and investigation notes go to chat or `docs/`, never to the message.
- Work on the branch the project names as default; if none is named, ask before branching from or merging into a long-lived branch.
- Differences between long-lived branches or deployment targets are intentional unless the project says otherwise. Never reconcile them in either direction and never merge between long-lived branches without an explicit request.

### 14. No documentation references in shipped code
- Never reference design docs (`doc 0021`, `docs/0011-...md`, `pkt 4`, `iter.4`, `M0..M7`) in anything shipped: comments, identifiers, log or error messages, commit messages, and everything on the front end - UI strings, tooltips, component names, view comments.
- Docs are not bundled with the product, so such pointers are dead noise for the reader.
- Say WHAT the code does and WHY in terms that stand on their own.
- Plain dates and ticket URLs are fine.

### 15. Documentation and plans
- Docs live in `docs/`, unless the repo already uses another location (then follow it).
- Classify by risk and architectural impact, not by file count. A multi-file rename is low-risk; a one-file schema migration is high-risk.
- Low or medium risk → implement directly, plan inline in chat, no plan document.
- High-risk or architecturally significant (new module or service, schema or public-API change, security, migration) → write a plan document before any code.
- Naming: follow the repo convention; otherwise `docs/{NNNN}-{title}.md`, NNNN = highest existing number + 1.
- The plan covers: expected change, how, why, verification, success criteria. Migrations and public-API changes also cover rollback, backward compatibility, rollout order and validation against real data.
- The document format (required sections, spec anatomy) lives in the `writing-spec` skill - load it before writing a plan document, do not restate it here.
- Write it for a junior agent: what, how, why, with `- [ ]` checkboxes marked `[x]` as they complete.
- User review before implementation is required only for high-risk or product-changing work.
- One document per work item: update the existing plan, do not create a second one.
- Follow-up fixes extend the same report or plan as a numbered round; each fix gets a result: done, partially done (why), not done (why).

### 16. Dependencies are a decision, not a shortcut
- Before adding a library, check that the stdlib, a framework already in the project or an existing helper here cannot do it. Name what you checked. A dependency added to save twenty lines is rarely worth it.
- Before proposing one, verify it is maintained, compatible with the versions already pinned here, and that its license fits a commercial closed-source product. Say so explicitly when any of these is unclear.
- A major version upgrade of an existing dependency is a contract change: its own work item, never a side effect of an unrelated task.
- Never edit a lockfile by hand. Change the manifest and let the tool regenerate it.

