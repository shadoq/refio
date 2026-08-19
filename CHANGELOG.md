# Changelog

All notable changes to Refio are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

## [0.0.1.16] - 2026-08-19

### Added

- A per-provider concurrent-request ceiling (`providers.max_concurrent`, default 4) throttles simultaneous outbound LLM streams to a single cloud provider, so a parallel-subagent fan-out no longer opens an unbounded number of connections and trips the provider's rate limit (OpenRouter 429s). Ollama is unaffected (it keeps its own per-endpoint gate). Composes with the existing 429 retry: the gate prevents most bursts, the retry catches any that slip through.
- `read_file` tool bubbles gained an expandable "Read content" panel showing the full text exactly as it entered the conversation (line-number prefixes, `[Lines X-Y of Z]` markers), not just the "N lines read" summary. Carried on a transient `ToolCallResult.fullOutput` (built for the view, never persisted) and populated only for `read_file`, so other tools keep their compact summary.
- Vertical navigation rail on the left of the tool window (`RefioRail` + the `RefioScreen` enum) replaces the horizontal tab strip: one icon button per screen, `Alt+1..8` keyboard navigation preserved, and a small accent dot marking a background screen that got new content. Advanced-only screens (Context, Agents, RAG, Debug, Logs, API) still appear only with "Advanced View" on, and leaving advanced mode while such a screen is open now falls back to Chat instead of stranding the user on a screen with no button.
- "Now running" bar above the chat transcript (`NowRunningBar`) while a turn is in flight: current step, current tool, elapsed time and a Stop button, all without scrolling. The spinner stops (while the bar stays up) in the waiting-for-permission / waiting-for-user phases, so a turn stalled on the user does not look like one stalled on the engine. Its display rules live in a pure `NowRunningState` mapping that is unit-tested without a UI.
- Plan summary header above the execution step list (`PlanSummaryPanel`): how many steps passed or failed and where the time went, as a one-line verdict plus a segmented progress bar, instead of having to read every step.
- Embeddings from any OpenAI-compatible server (`OpenAICompatibleEmbeddingProvider`), selected as `models.embedding_model: openai_compatible/<model>` and pointed at `providers.embeddings.embeddings_base_url` (+ optional `embeddings_api_key`). The two built-in providers each pin a protocol decision - the OpenAI one pins `api.openai.com`, the Ollama one speaks Ollama's `/api/embed` - so neither could reach a self-hosted embedding model. The endpoint is configured separately from the chat provider because embeddings commonly run as their own process on another port; the vector width is learned from the last successful response rather than looked up in a table, and returned vectors are only reordered by `index` when it forms a complete permutation (a server that omits the field sends `0` for every entry, which would otherwise collapse the whole batch onto one vector).
- `providers.generic_openai.generic_openai_context_size` (default 32768) declares the context window of an OpenAI-compatible server, because `/v1/models` on llama.cpp and friends does not report `context_length`. Same role as the existing Ollama and LM Studio overrides, and likewise without a validator so a very large window can be declared as-is.
- `providers.generic_openai.generic_openai_raw_request` sends the chat request without our sampling parameters (`temperature`, `max_tokens`, the non-standard `request_id`) for servers that pin generation settings themselves. `stream` / `stream_options` and `tools` / `tool_choice` are deliberately kept - dropping the first pair loses real token accounting, dropping the second breaks AGENT mode. Scoped to `generic_openai`, not to other providers sharing the same adapter.
- Settings -> Providers (and the TUI settings screen) gained a Context Size dropdown per local provider and the raw-request toggle, plus a Model field for the OpenAI-compatible provider. Changing a context size invalidates the cached model list. A value in `config.yaml` that is not in the offered set (legitimate, since these keys have no validator) now shows the nearest lower offered size instead of leaving the field on whatever it was built with - the rule is a pure `nearestNumericOption` with tests, since the plugin test source set cannot construct Swing components.
- Responsive layout: the panel picks a width band (narrow / normal / wide) from a single root-level resize listener and reapplies only when the band actually changes. In a narrow dock the rail buttons shrink, the status bar collapses to the health dot, context percentage and cost, and the composer drops the Send/Stop captions to icons with a non-stretching model dropdown - which is what used to push a horizontal scrollbar into the tool window.
- A soft guard for the "defines agents but never runs one" pattern: after two consecutive `manage_subagent` create/update calls with no `invoke_subagent` between them, the turn injects a short guidance note naming the agents already defined. Defining an agent only stores a definition - a local model asked to "scan the project with subagents" spent four turns creating and re-creating the same agent and produced no analysis at all. The note is advisory (it never ends the turn), fires at most once per turn, and is cleared by any `invoke_subagent`.
- Per-turn budgets `agent.max_turn_tokens` (default 1200000) and `agent.max_turn_minutes` (default 45) stop a turn that keeps spending without converging. `agent.max_cost_usd` cannot do that for a locally served model: the call costs nothing, so the dollar ceiling never trips and the iteration cap is the only brake left - raising that cap removes the last one. Measured on the scenario suite, one turn burned 1.56M input tokens against a 50k-150k norm at $0.00 and still failed. The two budgets catch different failures (tokens bound a turn that keeps generating, wall-clock bounds one stuck waiting), they are checked at the top of each iteration so the turn stops before paying for another call, and either is disabled by setting it to 0.
- Every tool call now runs under a timeout (2 min in PLAN, 5 min in AGENT, 3 min for network tools), enforced on the executing thread with a real interrupt. The setting existed but was consumed only by an executor that is never instantiated, so nothing bounded a single call: a `grep_search` pattern over one very long line costs quadratic time (100k characters measured at over 8 s, and the 2 MB file limit allows far worse) while blocking its thread, so cancelling the turn did not stop it either.
- Agent file changes can be rolled back from the snapshots that were already being written on every edit and never read: `planRestore` reports what a restore would do to the working tree, `restoreSnapshot` performs it. Known limits, all stated rather than hidden: creating a file cannot be undone (a snapshot has no row for a file that did not exist), a restore is not atomic across files and takes no file lock, and it does not refresh the RAG index or the agent's working memory.
- `rename_symbol` refuses a project-wide rewrite unless it is asked for: a rename whose usage list spans more than 20 files needs `confirm_wide_rename`, and the count is established before anything is written. The text engine (the default outside the IDE) rewrote every file containing the word - comments, strings, YAML and SQL included - with no dry run, no confirmation, and a snapshot covering only the anchor file, so a failure halfway left the project half-renamed with no list of what had been touched.
- The approval prompt shows the command that is about to run (up to 300 characters) instead of just the tool name. The consent layer was designed around the user reading the line before approving, while the UI could not distinguish `cat README.md` from `cat ~/.ssh/id_rsa`.

### Changed

- Tool calls in the transcript are now single 24 px rows (`ToolCallRow` + a precomputed `ToolCallRowView`) instead of cards: status, tool name, target, diff size and duration on one line, expanding on click into an IDE diff when a pre-write snapshot exists and the raw output otherwise. The detail is built on first expansion, so a session with fifty calls holds fifty labels rather than fifty editors, and the expanded state survives a re-render of the transcript.
- The execution screen shows a plan as one 26 px row per step (`StepRowView` + `StepListRenderer`) instead of ~95 px cards that repeated their own duration twice; a selected failed step explains itself in the lower half of a splitter, the full payload stays behind the details dialog.
- Session history is a flat two-line list (`SessionRow` + `SessionListRenderer`) instead of cards, so a screenful shows about a dozen sessions instead of three. Row actions (load, pin, delete) moved to the toolbar and the context menu, and the mode/status filters collapsed into a single popup button so the toolbar still fits a narrow dock.
- The status bar was reduced to what actually fits a 22 px strip - engine state, context fill, requests, tokens and cost, all abbreviated - with exact numbers, cache reads and global totals moved into the tooltip behind a trailing "more" icon. Engine flows push far faster than a human reads, so updates are now buffered and repainted at most four times a second. Number formatting moved to a pure `StatusBarFormat` object covered by tests.
- The settings panels (General, Advanced, Context, Providers, Models, Tools, Docs, MCP, Prompts, Subagents, Theme) were rebuilt on the Kotlin UI DSL, so label alignment, row spacing and the grey explanation lines come from the platform instead of hand-tuned insets and stacked struts. Values still save on change (no Apply button), so the controls carry listeners rather than DSL bindings. The category column became an icon-only strip with tooltips, because the strip is narrower than any of the category titles.
- Every settings page now shrinks to a docked tool window instead of assuming dialog width. Shared helpers in `SettingsLayout.kt` back it: `settingsForm { }` trims the DSL's per-group left indent (about a checkbox wide, which a narrow dock cannot spare), `settingsScrollPane()` wraps a page in a `Scrollable` view that adopts the viewport width while the content can honestly shrink to it, and `fitColumns` / `showFullValueOnHover` give every settings table small column minimums plus a tooltip carrying the full value of an elided cell. Applied across Tools (tools + command rules), API logs, Docs, Models, Subagents, Providers, Context, Advanced, General, MCP, Prompts and Theme. Tables also lost their fixed preferred width, so a table no longer decides how wide the whole page is.
- `ModelWindow` gained a step between the provider override and the global fallback: a window discovered from the provider's own model listing (read from the cache the listing already populated, so the hot path still does no I/O). Models our static tables do not know - new cloud releases, anything served locally - used to fall straight to the global default, which under-used large windows. `general.max_context_size` now also acts as a ceiling whenever the user set it explicitly ("never send more than this"); left unset it stays a last-resort fallback and caps nothing.
- `ChatService` no longer caps the resolved window by `general.max_context_size` a second time: the resolver already applies that ceiling when it is set, and with the key unset the extra cap shrank an explicitly declared provider window back to the built-in default.
- The session list renders in two shapes: stacked (status, title, mode over timestamp / duration / tokens) while docked, and single-line with the metadata right-aligned once the panel is wide enough, which halves the row height. The title budget is derived from the actual row width instead of a flat 42 characters, so a wide panel no longer shows ellipsised titles next to hundreds of empty pixels.
- The status bar shows the context fill in tokens next to the percentage (`47K/128K  38%`), because a percentage alone does not say how much room is left and the same percentage means something different on a 32K and on a 1M window. Whole thousands, truncated so the window never reads as larger than it is (32768 shows as `32K`); the metric is omitted while no model context window is resolved yet, and it collapses away with the rest of the context group in a narrow dock.
- The settings titled border uses a tighter horizontal inset than vertical (`LCATheme.createSettingsBorder`), because in a docked tool window width is the scarce dimension and height simply scrolls.
- The GPT-5.1 and GPT-5.2 Codex variants were removed from the OpenAI direct-API whitelist: that API no longer serves them for our key type (`/v1/responses` answers 404 `model_not_found`, streaming yields an empty completion). They stay reachable through OpenRouter (`openai/gpt-5.1-codex...`), which uses the pattern-based whitelist, and Codex 5.3+ still works on the direct API and stays listed.
- The streaming assistant text bubble now shows a live char counter (`Generating... · N chars`) instead of a static label, so a slow producer (e.g. Ollama native-tools) no longer looks hung.
- Next-speaker-judge re-entry is capped at a single nudge once a substantial text deliverable is already present (>= 100 chars), instead of the extended budget of 2. Removes the duplicate whole-turn regeneration and the second "Agent guidance" message on purely-text turns; the extended budget still applies to early, still-undelivered turns.
- Subagents now always have the safe internal tools `think`, `tasks` and `memory` available, even under a `tools:` whitelist that omits them (these have no side effects and no network egress). This fixes personas whose body instructs `think(...)` while the whitelist forbids it - the model learned about the tool from its own prompt and looped on a call the harness kept rejecting ("Tool 'think' is not available to the subagent"). Egress tools (`web_search`, `http_request`, `fetch_webpage`) and the shell are still granted only when explicitly whitelisted. A single source of truth (`SubagentToolFilter.isToolAllowedUnderProfile`) now backs the system-prompt tool matrix, the JSON-call validation and execution, so they can no longer disagree about what a subagent may call.
- A read-only subagent (one whose profile grants no file-write/exec tool) that produced a substantial prose report is now accepted as delivered instead of being re-entered by the completion judge. Such a subagent can only ever deliver prose, so demanding "one more tool call" merely pushed it to hallucinate tools it lacks; a short intent stub still gets the normal re-entry, and write-capable subagents are unchanged.
- A top-level AGENT turn whose deliverable is prose - an analysis, review or report the task never asked to be written to a file - is now accepted as done once it produced a substantial answer (>= 600 chars, no file write), instead of being re-entered by the completion judge and finalized INCOMPLETE with a spurious "Refio prompted the agent to finish the remaining steps" guidance note. Observed on a subagent-driven project scan: the main agent compiled a full report, but the judge (which only saw the opening lines) read the intent preamble the model buried it behind and re-entered. A short intent stub stays below the bar and still gets the normal re-entry; a turn that wrote a file is unchanged.
- The completion judge now reads the agent's whole reply (folded to head+tail only when it exceeds 4096 chars) instead of the first 2000 chars, so a completed report is no longer judged on its opening lines while its concluding result sits just out of view.
- `manage_subagent(action="create")` is idempotent: re-creating a name that already exists with the same definition succeeds as a no-op, and with a different definition applies the change as an update and says so, instead of failing with "already exists, use action='update'". For an agent, "an agent by this name must exist" is a desired state rather than an operation, and the hard error cost a full iteration on a harmless repeat. Builtin names are still protected - creating over one is refused.
- `manage_subagent`, `memory` and `tasks` now name their valid actions when the `action` parameter is missing, instead of answering a bare "action required". Providers do not enforce the schema's `required`, so a model that omits it has nothing but this message to learn from, and guessing costs it a whole turn.
- Directory listings (`read_directory`, `file_search`) are compressed structurally instead of being summarized by the weak model: entries are grouped per directory with counts, total size and a sample of names. Prose summarization destroyed exactly what the agent needs to pick its next read - a 29 KB recursive listing came back as 562 characters of narration containing no directory name at all - and cost a model call on top. The same grouping is applied in the recent-work section, where the raw listing used to be re-sent verbatim on every following iteration (about 40 % of the context budget in the observed session); the full listing stays retrievable via `memory(action="get_subtask_output")`.
- The chat transcript is single-column at every panel width. The side timeline column that appeared in a wide panel duplicated what the transcript and the "now running" bar already showed, so it was removed along with its saved splitter proportion; the "now running" bar is no longer suppressed in a wide layout.
- The turn iteration cap moved from 100 to 200 and its warning from 80 to 50, so a long agentic task has room to finish while the operator hears about it far earlier. The cap is no longer the only brake: the per-turn token and time budgets above bound the work in units that mean the same thing for a billed and a local model.
- A project's `.refio/config.yaml` finally takes effect. Built-in defaults were seeded as application-scope database rows and the resolver puts the database above YAML, so for the nine keys that have a YAML accessor (`general.no_egress_enabled`, `general.execution_mode`, `general.reasoning_effort`, `ui.selected_mode`, `models.embedding_model`, `general.format_markdown`, `general.streaming_enabled`, `general.advanced_view`, `providers.zai.zai_base_url`) the project file was never consulted and "Export to project config" wrote a file that could only be written, never read. Project YAML is now materialized into the project scope, which sits above the seeded defaults.
- `tools.permissions` in `config.yaml` is read back instead of only being written. The export side worked (the section is generated, emitted and merged) and the documentation promised the round trip, but nothing ever loaded it, so "Reload from YAML" silently ignored every per-tool permission.
- An MCP server in CONTEXT mode no longer calls every tool it exposes on every turn with the raw user prompt as the argument, including its write tools and including CHAT, which by contract has no tools. These calls bypass the permission and approval layers entirely (context tools are not in the registry), and the `contextToolName` field that the UI presents as "use only this tool" was ignored on this path; it is now honored.
- The next-speaker judge no longer accepts a short reply as proof that the user speaks next. The rule was a 30-character length test standing in for a semantic one, and the stall examples cited in that same file ("Now let me run git branch...") are themselves short enough to pass it.
- The intent-announcement heuristic was removed from the completion judge: matching prose that looks like "I will now do X" was guessing at meaning from surface form, and a deliverable-aware finalization already decides the same question from what the turn actually produced.

### Fixed
- Opening or refreshing the Context panel no longer compacts the conversation. The runtime-prompt preview calls the same builder as the turn loop, which ran `ConversationSummaryService` unconditionally, so rendering "what would be sent" spent a weak-model call and wrote a summary row into a conversation nobody had advanced. Compaction is now opt-out per call (`allowSummarization`) and the preview opts out; the turn loop is unchanged.
- A failed compaction no longer costs the turn its project context. The summarizer talks to a weak model, and an outage there (provider down, no-egress, rate limit) propagated out of the context builder, whose caller falls back to raw message assembly - so a failure to SHORTEN the history silently dropped the entire project context from the prompt. The failure is now caught and the turn continues on the uncompacted history.
- A multi-agent run that fails now records why. The router copied only the turn's `success` flag and dropped every signal explaining it, and the run.json error list was built solely from agents carrying an error string - so an agent that came back FAILED without one left the run with an empty error list and no trace of the cause anywhere but the final answer's text.
- `run_terminal_command` now reports the exit code of a failed command instead of passing its raw output on as the failure reason. A `grep -c` with no match exits 1 while printing a valid count of `0`, which previously reached the agent as a bare `Error: 0` - an answer disguised as a malfunction.

- A locally served model was rejected before the request left the client ("context too large") no matter how the window was configured: `TokenEstimator` owned a second context-window resolver whose last resort was a hardcoded 32768, so the pre-flight check and the context budget disagreed - the budget sized the prompt against the configured window while pre-flight refused it. `TokenEstimator.getMaxContextForModel` is now a thin wrapper over `ModelWindow.resolve` (the single resolver), and without a `ConfigService` the pre-flight check is skipped with a warning rather than run against an invented limit; the provider still enforces its own.
- `general.no_egress_enabled` blocked a self-hosted embedding endpoint, which made a fully local RAG setup unusable under the very setting meant to keep it local. Embedding calls now go through `NetworkPolicy.assertRemoteEgressAllowed`, where no-egress means "do not leave my network" rather than "do no I/O"; tools that always reach the public internet keep the strict gate. What counts as local is now one shared `NetworkPolicy.isLocalTarget` (localhost, `0.0.0.0`, `192.168.*`, `10.*`; an unparsable URL counts as remote), which the LLM path reuses instead of its own copy of the same check.
- A turn that wrote its file and then repeated its sign-off was reported as failed. The cross-iteration text-repetition guard ended the turn unconditionally, but repeating itself is what a model does once the work is done and it has nothing left to say - the self-verification phase right after a write is where it shows up (observed on a local model building a single-page app: the file landed, the model re-stated its sign-off twice, the turn came back FAILED). The abort is now deliverable-aware like every other terminal abort: with a file already on disk it finalizes as done, and with nothing written the prose loop is still stopped.
- A finished turn was reported as failed, discarding the answer the user had already seen. When the completion judge re-enters a turn it stashes that answer and drops native tools so the retry runs on the JSON contract; a model that then returns an empty envelope hit the empty-content give-up, which only recognised a file write as a deliverable. Anything delivered another way was lost - observed on a local model where two subagents fixed both files and the parent turn still ended FAILED, and on a read-only analysis whose whole deliverable was the text. An answer the guardian is holding now counts as a deliverable: the turn finalizes with it (persisted once, never duplicated) instead of failing. A turn with no stashed answer and no write still fails as before.
- The "now running" bar stayed up after the agent stopped, showing a live step, the last tool and a Stop button for a turn that had already finished (observed after a guardrail loop-abort inside a subagent). The turn-state flow was reset on only 4 of the roughly 20 terminal exits of the turn loop, so every abort path left the last running phase behind. The reset now happens in the one place every exit already goes through (`TurnPersistence.finish`), and only for the top-level turn - a nested subagent shares the flow with a parent that is still running.
- The category icons in the settings strip jumped a few pixels sideways on hover: a cell was as wide as the strip itself, one pixel more than the viewport left by the strip's right-hand line, so `JBList` treated it as truncated and re-painted it in an offset popup. The cells now take their width from the list, and the expandable-item handler is off for a strip that has nothing to expand (the category name arrives as a tooltip).
- A settings page in a narrow dock silently lost its right-hand side: the scroll container disabled the horizontal scrollbar (`HORIZONTAL_SCROLLBAR_NEVER`) while laying the form out at its preferred width, so whatever did not fit was unreachable. The page now follows the viewport width down to a 320 px content floor and only falls back to a horizontal scrollbar below it, so nothing is cut off without a way to get at it. Covered by `SettingsLayoutTest` (the width-tracking and scroll-policy rules are checked without a running IDE).
- Settings tables clipped their trailing columns at dock width (the Tools table's Plan/Agent mode selectors, the API-log status column), because column minimums were sized for a dialog. Minimums are now small enough for all columns to survive, and hovering an elided cell shows its full value.
- The Docs settings actions overflowed a docked tool window with three buttons in one row; the destructive action moved to its own row.
- The `[SLOW_TOOL]` log warning fired on every `invoke_subagent` / `delegate_to_strong_model` call, misattributing an expected multi-minute duration to "VFS/file-lock contention" or an "OllamaRequestGate queue". These tools run a whole nested turn loop, so running for minutes is normal, not a contention symptom; the warning is now skipped for them and still fires for genuinely slow read/edit tools.
- After `advance_code_editing` finished, the "Generated content" panel showed the `edit_description` parameters instead of the generated code: the code lived only in the streaming transient while its persisted display twin carried empty content. `reconcileMessages` now keeps the richer completed edit transient over its blank persisted twin. (In-memory only; after a full session reload the bubble falls back to the diff summary.)
- The ACE "Show content" collapsible did not paint until the tool window was resized. The cached panel is now force-rebuilt once when a message's content goes blank -> non-blank, so the expand affordance appears immediately.
- The `edit_description` parameter was rendered under a "Generated content" label. The collapsible content panel and preview metadata label are now parameterized, so a params block is titled by its key and the read/diff metadata line is labelled per source.
- `delegate_to_strong_model` with `allow_tools=true` always aborted on its very first call with a false "Subagent recursion detected for 'strong-model'". The tool pre-added its own name to the child's ancestor chain, which the recursion validator then matched against the child itself. It now passes the ancestor chain unchanged (the current name is added by the turn executor when spawning), so tool-enabled delegation works while genuine strong-model -> strong-model recursion is still caught.
- Read-only analysis subagents were killed mid-run by the byte-identical-output loop guard: subagents use a tight threshold of 2, and re-reading the same file (partial read, then full, then a full re-read to double-check) produced identical output twice and aborted. Read-only tools (`read_file`, `grep_search`, `file_search`, `rag_search`, `read_directory`, `code_intelligence`) now use a separate, lenient threshold (4); write/exec tools keep the tight subagent threshold. A real read loop (4 identical reads) still aborts.
- The tool-error-rate guard could abort a turn that had just recovered: a burst of failures followed by a successful tool call still exceeded the error-rate threshold in the sliding window, so the turn was killed before it could act on its new (working) approach. The guard is now recovery-aware - it aborts only when the most recent tool call also failed, sparing the single iteration right after a recovery while still stopping sustained failure loops.
- A turn that repeatedly called tools it does not have (profile-blocked) could loop until manually cancelled: the error-rate window was diluted below threshold by the successful reads earlier in the turn, and the definitive-loop counter reset whenever the arguments varied. A dedicated backstop now aborts the turn (INCOMPLETE) after two consecutive blocked calls, pointing the model back to its available tools instead of burning iterations.
- A streamed assistant report vanished when a turn was cancelled (Stop) or hit its iteration limit: those exits persisted a hardcoded string and dropped the last streamed text, so the report the user was watching blinked out and was replaced by "Operation cancelled by user." The turn now persists the last substantial streamed prose on the cancel / max-iterations exits, so what was on screen survives.
- The context-overflow guard warned `[CTX] overflow` and flagged the run on prompts the provider actually accepted. It sized the window from a hardcoded per-model table that fell back to 128k for any model not listed (every newer OpenRouter model), while the context budget sized the same window correctly. The guard now resolves the window through `ModelWindow.resolve` - the single resolver the budget and auto-compaction already use - so the two can no longer disagree. The dead per-model table was removed.
- Provider ids are now canonicalized to lowercase where a `provider/model` string is first parsed, so the same model is no longer split across casings ("Openrouter/..." authored in a subagent's frontmatter vs "openrouter/..." from the request path) in metrics grouping, api-log filters and logs. Only the provider is normalized; model ids keep their casing.
- The Agents Graph left finished sessions and subagents showing `[RUNNING]` indefinitely: single-session and Task-tool subagent runs never emitted the AgentCompleted/AgentFailed the graph listened for (only the multi-agent runner did). Each run now emits a final `TurnEnded` at every exit (complete / abort / cancel / max-iterations) carrying its success, and the graph flips the node to COMPLETED or FAILED on it.
- Parallel subagents crashed on a database UNIQUE-constraint violation (`subtasks.task_id, order_index`). Subagents share the parent's task, and subtask order_index was allocated by reading the current max and then inserting at max+1 in a separate step, so two concurrent turns read the same max and collided. A new `SubtaskRepository.createNext` allocates the index and inserts as one retry-guarded step, re-reading the committed max and retrying on the collision (or a transient write-lock), so concurrent creates always land on distinct indices.
- A subagent turn that threw an uncaught exception (e.g. the subtask collision above) left its Agents-Graph node stuck `[RUNNING]`: the exception escaped the turn loop before any terminal event was emitted. A backstop now emits the final `TurnEnded(success=false)` on that path too, then rethrows the original error unchanged, so the node is flipped to FAILED while the caller still surfaces the real error.
- A subagent saw every other agent's plan in the `<agent_plans>` prompt section: plans are keyed per agent, but the section was built for the whole task, so a subagent (which shares the parent's task) saw the orchestrator's and its siblings' plans. That is context noise and could trick a weak model into calling `tasks(update)` on a step it never created (its own plan is empty), failing with "Step N not found". A subagent now sees only its own plan; the top-level orchestrator keeps the full cross-agent view.
- A turn burned two full iterations after the completion judge re-entered it and the model answered with nothing at all: the empty reply was met with format nudges ("reply with JSON only"), each replaying a full-size prompt for another empty reply, before the loop restored the stashed answer anyway. When the judge is already holding the answer the user saw, an empty reply now ends the turn immediately with that answer - the re-entry was the last safety net, so there is nothing left for a nudge to rescue. A recoverable envelope hidden in the model's thinking still wins over the shortcut.
- Subtask kinds now cover every registered tool (`manage_subagent`, `think`, `tasks`, `memory`, `code_intelligence`, `rag_search`, `find_usages`, `rename_symbol`, the process and messaging tools, `llm_call`, `delegate_to_strong_model`, `ask_user`, `sleep`). All of these were stored as the generic `PLAN_STEP` with a warning on every call, which left the subtask list unreadable - three of four rows saying "PLAN_STEP" - and made working memory skip them when it was rebuilt from history after a restart.
- `advance_code_editing` silently truncated a file at the first nested code fence and still reported "File edited successfully". The extraction used a lazy quantifier, so generated content containing a triple backtick in column 0 ended the match there and only the part before it was written; nothing downstream caught it, because the syntax check only asks whether the result is non-empty and the agent is told a successful write is complete. Markdown, prompt files and documentation were the real carriers.
- A mid-stream error from OpenRouter was swallowed and the truncated answer came back as a success. The stream handler rethrew only `IllegalStateException`, while the adapter had been changed to throw the mapped provider error, which is a plain runtime exception - so the very commit that added 429 handling disabled the retry it was written for. When the error arrived before any content the turn failed with a completely misleading diagnosis ("the prompt exceeded the model's context window"), and after partial content it passed as complete.
- `multi_edit` replaced the first occurrence of a string without checking that it was unique, while its sibling `code_editing` refuses the same case with "String appears N times in file". The schema offers no `replace_all` or `occurrence` parameter and the prompts never mention uniqueness, so the model had no way to know; with the tool at ON in AGENT there was no human checkpoint either.
- BLOCK command rules could be stepped over with a prefix. Patterns were compiled without multiline, so `^` anchored only at position 0 and `env rm -rf X` matched no rule while carrying none of the shell operators that hold a line back. This does not bypass approval by itself, but it downgraded a hard prohibition to an ordinary prompt: after one "Trust" click or with the tool at ON, BLOCK was the last barrier and a prefix removed it.
- Session trust rules now actually end with the session. The reset existed and was tested but had no production caller, so "trust for this session" meant until the project or the process closed.
- `general.no_egress_enabled` could be defeated by a hostname. The local-target test compared the URI's host text, so `http://10.attacker.example.com:11434` counted as a private address and the full code context was sent to it - and the endpoint is settable from a project's own `.refio/config.yaml`, so a cloned repository could supply it. The same test also blocked legitimate LAN names, which is why it is now one shared classifier used by every egress gate.
- The streaming execution path did not enforce a tool set to OFF, only the mode check, while `advance_code_editing` and `multi_line_editor` always take that path when a listener is attached (the plugin and the TUI). Turning one of those two off mid-turn had no effect on the write that was already under way.
- A failed MCP connection leaked its process or its HTTP clients for the lifetime of the application: the connection object was only put into the map after a successful `connect()`, and the failure path had no `finally`, so nothing could ever disconnect it. The dominant misconfiguration failures (handshake timeout, a server that does not speak MCP, 401/404, a wrong URL) all land there, the stdio child cannot exit while the writer holds its stdin open, and the status was reported as DISCONNECTED rather than ERROR so the probe waited out its full timeout.
- `MCPManager.shutdown()` did nothing in the CLI: it was called without the project id it had been initialized under, so it mapped to a global key that the CLI never creates and returned immediately. It sits on the shutdown path of every CLI and TUI mode, so this missed every run.
- Recreating a project's router dropped all its MCP tools while continuing to report a non-zero tool count. Opening Settings or the RAG view before the chat window was enough to trigger it, and the tools stayed gone for the rest of the IDE session.
- Two concurrent `connectServer` calls for the same server could start two processes and orphan one, because the presence check and the insertion were separated by a suspending connect with no lock between them.
- Concurrent `send()` on the stdio transport corrupted the JSON-RPC framing (the write / newline / flush trio is not atomic), which left both requests unresolved and both callers waiting out a 30 s timeout. Parallel tool execution is on by default in PLAN and AGENT and read-mode MCP tools qualify for it, so this needed no unusual configuration.
- `advance_code_editing` and `multi_line_editor` rewrote every line ending in the file from CRLF to LF. Both bypassed the helper that exists for exactly this, and since the diff also normalizes line endings the change was invisible in the diff and in the no-op check - a tool could report "No changes applied" after rewriting the whole file.
- A large edit could take the whole process down with an out-of-memory error after the file had already been written. The change summary was computed unconditionally after the write using a full LCS matrix (about 4*L^2 bytes: a 20000-line file needs 1.6 GB, and the 2 MB file limit lets such a file through), and since that error is not an `Exception` nothing caught it - the file was changed on disk, the turn died without a tool result, and the agent never learned the edit had succeeded. In the plugin this put the whole IDE at risk.
- A failure to connect to the LLM was never retried. The connection failure is its own error type rather than a subclass of the general LLM error, so it fell through to a message-pattern test that its own rendered text ("LLM server is not responding at ... Is Ollama running?") does not match. A connect timeout against a model that is still loading was mapped to the same type before the timeout branch, so it inherited the same non-retryable treatment while an ordinary timeout was always retried.
- The embeddings circuit breaker counted every HTTP failure twice, so it opened after three errors rather than the configured five. The same duplicated pattern was present in five methods.
- A RAG reindex could leave a file with a fresh checksum and no chunks, permanently. The checksum, the deletion of old chunks and the insertion of new ones each ran in their own transaction, and classification compares only the checksum, so such a file was treated as unchanged forever with a manual Clear Index as the only way out. The mirror case (new checksum, old chunks) was possible too, and the same pattern was fixed in the file-analysis and documentation indexers.
- RAG search materialized every chunk above the score threshold, with content, even though a heap a few lines earlier had already reduced the set to the top K.
- A repeated-text abort discarded the deliverable in PLAN and in subagents: the "did this turn produce something" test was handed an empty string instead of the reply, so the prose branch was unreachable and only a file write counted. The plan text stayed in the transcript but the turn was marked failed, so its steps were never created, and a subagent returned a loop message to its parent instead of its report.
- `working_memory.max_facts` had no effect - the service was constructed without the configuration it needed, so the limit was always the hardcoded 20 - and per-task entries were only released when the IDE closed rather than when a project did.
- A queued approval dialog could expire before it was ever shown: the timeout started when the request was queued rather than when it became visible, so spending more than five minutes on the first dialog auto-rejected the second one behind it.
- Disconnecting an MCP server left its pending requests hanging for the full 30 s timeout with a misleading "request timed out" instead of a disconnect error, and cancelling a turn (as opposed to timing out) left the request registered.
- Background processes that daemonize themselves survived application shutdown: the shutdown hook checks whether the launched shell is still alive, and for a self-backgrounding command it never is, so the surviving grandchild was reparented and left running. The terminal tool already tracked descendants for this reason; the process manager now does too.
- Session cost totals were always $0.00 for cheap models, because the accumulator truncated to whole cents instead of rounding, so every call under one cent counted as nothing.
- The build verifier could hand the model a truncated list of build errors to fix: it read an unsynchronized buffer without waiting for the reader to signal completion.
- A subagent killed by a guardrail stayed marked as running in the agents graph forever, because most of the turn loop's exits never emitted the terminal event. All exits share one finalization point, so the emission now happens there.
- Incremental tool-call streaming from Ollama dropped every call but the last, because the accumulated list was cleared before each append.
- The chars-per-token prior treated an unrecognized model name as a loose cloud tokenizer, which under-counted a dense local model by about 9% - exactly where the estimate has to be honest, because that estimate is the only way to detect that Ollama silently truncated an oversized prompt. Locally served models routinely carry names that match no known family (`ornith:35b` is a qwen-family mixture-of-experts underneath) and `gpt-oss` matched the "gpt" fragment and was given the cloud ratio while running locally. Cloud tokenizers are now named explicitly and everything else is assumed dense, because over-counting only leaves a sliver of the window unused while under-counting costs correctness.
- The chars-per-token calibrator learned from truncated requests. A provider that truncates reports the length after truncation, so pairing it with the full character count taught an inflated ratio, which lowered every later estimate, which blinded the overflow guard and told the context budget it could pack even more text in - each truncation made the next one harder to see. Samples from a request that may have been cut are now discarded.
- A refusal from an approval policy ended the whole turn as though the user had asked to stop. That is right when a human clicks reject and wrong when a rule declines one command: in a headless run a single unmatched command killed everything after it. Measured across a 258-run scenario campaign, 10 runs died this way and 8 of them had already written their deliverable and were only cleaning up. A policy denial is now handed back to the model as a failed tool result naming the blocked call, so it can take another route; a human rejection still ends the turn.
- The system-prompt token estimate was computed without the model id, so it used the flat default ratio and ran about 9% low for dense local models.

### Removed

- The per-turn token ceiling (`agent.max_turn_tokens`) is gone. It was added to bound a locally served model, whose calls cost nothing, but a large turn is normal there - the tokens are free - so it stopped turns that were still making progress more often than it caught runaway ones. The per-turn wall-clock budget (`agent.max_turn_minutes`) stays and covers what the iteration cap cannot see: a turn stuck waiting rather than iterating; its default drops from 45 to 30 minutes. An `agent.max_turn_tokens` entry left in `config.yaml` is now ignored.
- `agent.max_iterations` is gone. The key was seeded, validated and printed by `--print-config`, but nothing ever read it: the real cap is 200, set per mode in `TurnLoopConfig.plan()/agent()`, so `--config agent.max_iterations=80` silently did nothing. Removed rather than wired up, because the iteration cap is a runaway backstop that the abort guardrails sit below, not a budget worth tuning per project. A leftover entry in `config.yaml` is ignored.
- Eleven fields on `TurnLoopConfig` that no code read: `enableAutoCompaction`, `compactionThreshold`, `summarizationThreshold`, `warningThreshold`, `maxFormatRetries`, `enableWorkingMemory`, `workingMemoryMaxEntries`, `enableVerification`, `verificationIterationThreshold`, `enablePromptCaching`, `cacheablePrefix`. Each is already owned elsewhere - compaction by `ConversationSummaryService`, tool-result shortening by `tool_summary.*`, working memory by `working_memory.max_facts`, post-turn checks by `verify.*`, prompt caching by the Anthropic adapter's `cache_control` markers, the iteration warning by the prompt provider that counts remaining iterations - so the presets were a second set of numbers that looked like configuration and disagreed with the live one (`enableVerification = false` next to `verify.enabled = true`). No behavior change.

## [0.0.1.14] - 2026-07-22

### Added

- `find_usages` tool - lists every use of a symbol (`file:line` + snippet + count, capped by `max_results`).
- `rename_symbol` tool - project-wide rename from a `file`/`line` anchor; reports occurrences changed and files touched.
- Structural-refactor engine with two backends: identifier-boundary-aware text (CLI/headless) and IntelliJ PSI (semantic, updates references), with PSI falling back to text when a symbol can't be resolved.
- Deterministic post-turn verification (`TurnVerifier`): runs the project's build/test after a write, feeds failures back to the model for up to `verify.max_repair_rounds`, else marks the turn `VERIFICATION_FAILED`. Keys: `verify.enabled`, `verify.command`, `verify.max_repair_rounds`.
- Verify-command autodetect when unset: `build.gradle*` -> `./gradlew build -q`, `package.json` -> `npm test --silent`, `Cargo.toml` -> `cargo build -q`.
- Pre-write baseline capture - an already-failing build/test isn't blamed on the agent's change.
- Diff preview on approval: write tools attach a `ProposedChange`, and the IntelliJ approval panel gained a "Show diff" button.
- Extended next-speaker-judge re-entry (up to 2 early in a turn, second nudge re-includes the JSON schema). Toggle: `general.judge_extended_reentry_enabled`.
- Actionable LLM errors with fix hints: `LLMConnectionFailed`, `LLMModelNotFound`, `LLMContextOverflow`.
- Headless CLI always writes a `run.json` even when a turn throws before the normal export (task forced `FAILED`, error appended).
- Verification metrics (`metrics.verification`) and a `VERIFICATION_FAILED` marker in `run.json`.
- E2e gates: `e2e-gate.sh` (N runs per scenario -> pass-rate table), `validate-scenarios.sh` / `validate-analysis-scenarios.sh` (deterministic, no LLM), `tui-smoke.py` (CLI in a pty).
- E2e suite: +27 scenarios, +22 prompts, +21 fixture trees, +11 golden dirs.
- TUI Settings Tools tab (per-tool ON/ASK/OFF), plus reset-all / export / reload-config actions and scroll-to-selection.
- Cached-token cost accounting: input tokens split into fresh / cache-read / cache-write subsets, each billed at its own rate, so a repeated (cache-served) prompt is no longer charged as fresh. Explicit cache-read prices for OpenAI, Anthropic (0.1x input) and Gemini (0.25x input); models without a configured cache price fall back to the full input rate (never a phantom discount).
- Cached tokens surfaced in the UI: a `cached_tokens` session counter (persisted via a new DB column) with a `💾` indicator in the TUI status line and the IntelliJ StatusBar, so a user sees how much of a turn was served from cache.
- `general.reasoning_effort` (OFF/LOW/MEDIUM/HIGH) replacing the boolean `general.thinking_enabled`; each level maps per-adapter to the provider-native knob (OpenAI/OpenRouter reasoning effort, Anthropic/Gemini thinking-token budget, Ollama on/off). Selectors added to the TUI settings and the plugin General settings.
- Subagent history isolation: every subagent turn gets an agent-instance id even when the spawning caller didn't assign one, so a subagent's intermediate steps no longer leak into the parent thread or inflate its token budget.
- Multi-agent debug export (`SessionDebugExporter.exportMultiAgent`) writes a per-agent snapshot (ordered by start time, aggregate status and token totals) for a multi-agent run.
- Two orchestration e2e scenarios (`subagent-locate-and-fix`, `subagent-two-file-fix`) with fixtures, prompts and golden trees, checking the parent stays on-task after a read-heavy delegation.
- `TransientErrorClassifier` - one shared definition of "retryable upstream failure" (bare HTTP 500 with an empty body, Cloudflare 520, Anthropic 529 overloaded), used by `LLMRetryHandler` and by `advance_code_editing` (which bypasses the retry handler).
- `advance_code_editing` salvages a truncated generation: when the stream ends without a closing fence but the body is substantial (2 KB+), the near-complete file is written and flagged instead of the whole edit being lost. A truncated block also short-circuits the extraction-repair loop, which would only truncate again.
- `advance_code_editing` retries a transient editor-LLM error with a bounded backoff (2 retries, 1 s / 2 s), but only while nothing has streamed to the UI yet, so a partial stream is never duplicated.
- Repeated-full-regeneration nudge: rebuilding one path whole-file 3x in a turn (`advance_code_editing` / `create_new_file`) injects a single soft SYSTEM hint to make a targeted edit or deliver. Targeted-edit tools never count, so ordinary iterative editing is unaffected.
- Native-vs-JSON tool-routing decision (`ProjectContextResponse.nativeToolsDecision`) shown in the plugin Debug panel, so an operator can see *why* a run took the JSON-in-text path without re-deriving the precedence by hand.
- `TaskStatus.CANCELED` recorded when the user presses Stop, instead of the turn being logged as `FAILED`.

### Changed

- Default models `qwen2.5:7b` -> `qwen3.5:9b` (chat/plan/agent/weak).
- Empty-content GiveUp is deliverable-aware: a write that already landed finalizes as `SUCCESS` instead of `FAILED`.
- Turn decision LLM call routes through `LLMRetryHandler`, so a first-call zero-byte stream abort is retried instead of killing the turn.
- LLM error mapping walks the cause chain, detects context overflow across Ollama phrasings, maps 404 to model-not-found, and includes the endpoint.
- OpenAI-compatible SSE parses each chunk once into a typed JSON object (Ollama, OpenRouter, others).
- `find_usages` preferred over `grep_search` for rename impact; `rename_symbol` over grep-plus-per-file edits.
- Hooks run on the IO dispatcher, so a blocking hook no longer stalls the turn.
- Agent-event queries load only the newest N (cap 5000); API-log fetch takes a limit (default 500).
- RAG cheaper on large indexes: anti-join for missing embeddings, keyset pagination, query norm computed once, reused content hash.
- Doc/file indexing batches chunk and embedding inserts (one lock per table).
- `.aiignore` matcher cached per project root, invalidated by mtime.
- IntelliJ minimum IDE raised to 2024.2 (`sinceBuild` 242, first line with JBR 21).
- ChatView resize debounced (300 ms).
- Chat render pipeline rewritten (IntelliJ): the message `StateFlow` is collected directly and throttled with a trailing delay (leading-edge throttle, one rebuild per 300 ms) instead of being hopped through a `MutableSharedFlow(extraBufferCapacity = 1)` + `sample`. `StateFlow` conflation guarantees the final frame lands, so a burst of streaming deltas can no longer swallow the last update. The field is now named `uiUpdateThrottleMs` - it is not a debounce and must not become one (a debounce waits for the stream to fall quiet, which never happens while a tool generates).
- ChatView collectors moved to `Dispatchers.IO`: on `Dispatchers.Default` a long tool stream kept the bounded pool busy and starved the UI collectors. The collector bodies only marshal work to the EDT, so the elastic pool is the right home.
- `kotlinx-coroutines-core` excluded from the plugin's runtime classpath (it is provided by the IntelliJ Platform). A second bundled copy caused a `ServiceLoader` clash that silently stalled `StateFlow` collectors. Compilation and the standalone CLI are unaffected.
- A tool bubble is marked live (`isStreaming` / `isToolStreaming`) at creation instead of at its first delta, and `MessageDispatcher.reconcileMessages` treats an `EXECUTING` tool call as live.
- Tool-call bubbles deduplicated by `toolCallId` on reload: while the call streams the in-memory bubble wins (it is the only copy with content) and its persisted twin is held back; once it finishes the persisted row takes over. Keyed strictly by `toolCallId`, never `agentName`, so earlier turns of a repeatedly-invoked subagent stay visible.
- Streaming deltas whose accumulated text has not grown are skipped instead of rebuilding the whole message list (safe: the text only ever grows by appending).
- Stop cancels the running turn `Job`, so cancellation propagates into the in-flight LLM HTTP call instead of only flipping UI state.
- `advance_code_editing` requests the model's full output budget; adapters clamp to the per-model limit. An explicit caller `maxTokens` is now honored up to the *model* limit across all adapters (Anthropic, Gemini, Ollama, OpenAI-compatible); `limits.max_output_size` is the default when no value is passed and the ceiling when the model's real limit is unknown.
- `RepetitionDetector` threshold raised 4 -> 32 identical adjacent blocks. Legitimate structured output (table cells, list items, data rows) has a small bounded run; a decoder loop is unbounded. Large-block runaway stays covered by the 128 KB size limiter and the 180 s wall-clock deadline.
- The model chosen in the dropdown is persisted to APP-scope `ui.selected_model` and applied to a new session, so the CODING slot (`advance_code_editing`) stops generating with a stale model while the turn LLM already uses the new one.
- `system-agent.md`: the "do not validate static content" rule is explicit that writing a static file finishes it - no re-reading, no parsing its inline scripts, no self-authored grep-for-feature checkers. A failing self-authored checker is almost always the checker being wrong, and chasing it is the most common way a turn spirals.
- CLI/TUI numeric formatting uses `Locale.US`.
- Hot-path regexes precompiled (`JsonExtractor`, `TokenEstimator`, `ToolResultCompression`, `ToolCallParser`); JSON extraction now single-pass balanced-brace.

### Fixed

- Chat bubbles appeared only after resizing the tool window or dragging the splitter. Two independent causes: the lossy `tryEmit` render hop dropped the last update of a burst, and `messagesPanel.revalidate()` stopped at the enclosing `JViewport` validate root, so the scroll pane never re-ran its layout. The whole scroll chain is revalidated now.
- The live char counter in `advance_code_editing` never moved. The tool bubble was created non-streaming, so a mid-turn reload dropped it from the list; every later delta then mapped over an id that was gone, producing an equal list that the `StateFlow` never emitted (observed: 3433 deltas over 62 s with zero UI updates).
- One tool call rendered as two bubbles after a mid-turn reload (the streaming transient and its persisted display row carry different ids).
- Stop hung on OpenAI-compatible streaming (Z.AI/GLM, LM Studio, OpenRouter, generic OpenAI): the SSE loop never received the cancel check, so the turn read the stream to completion before reacting.
- A bare upstream `HTTP 500` / `520` / `529` killed a whole session instead of being retried.
- Weak models on the JSON-in-text path that emit tool params as siblings of `tool` (flat action shape, observed on gemma4:31b) failed with "Missing required parameter"; the leftover keys are now mapped as arguments.
- A turn that already wrote its file then narrated "let me verify..." burned extra iterations: the guardian no longer drops native tool-calling after a landed file write, and the dangling intent stub is replaced with a factual completion note.
- Orphaned background-process hang: `run_terminal_command` force-kills the whole descendant tree and drains stdout on its own thread, so a backgrounded child (`python app.py &`) holding the stdout pipe no longer hangs the turn (`orphan_reaped` in metadata).
- `ProcessManager` kills the whole descendant tree on stop, reaps children via a JVM shutdown hook, and serves continuously drained output.
- Code-intelligence/refactor child processes drain output on a daemon thread and force-destroy on timeout instead of deadlocking.
- Concurrent LLM completions no longer lose metric updates - atomic in-DB increments; task stats via one grouped SQL `SUM`.
- `MultiAgentRunner` runs its completion path even when agent setup fails, so dependents don't wait forever.
- Verification still red after all repair rounds ends the turn as a real failure, not fake success.
- Cost overcharge on cached / repeated context: a cache-read hit was previously billed at the full input rate. Anthropic cache-read was 10x over (now 0.1x), and a real gpt-5.4-nano run now reconciles to the actual OpenAI bill instead of ~5x under.
- Corrected OpenAI list prices used for cost estimates (`gpt-5.4-nano`, `gpt-5.4-mini`, `gpt-5.5`).
- OpenRouter model resolution matches the most specific prefix over an ordered map, so `openai/gpt-5.1*` keeps its 400k context window and its own pricing instead of falling back to the 128k `gpt-` family.

---

## [0.0.1.12] - 2026-07-02

### Added

- Headless CLI flags: `--output json` + `--output-file` (per-turn `run.json`), `--debug-level`, `--config`/`--config-file`, `--print-config`, `--max-cost`, `--auto-approve "<regex>"`, `--no-egress`; `-v` streams live token deltas.
- Unified tool-call extraction logs a reason on every failed attempt (no more silent early finish when a model returns prose).
- Native function calling on OpenAI-compatible adapters (OpenRouter, Z.AI, Generic OpenAI, LM Studio) and Gemini.
- Native-tools fallback persists across restarts (`models.native_tools_fallbacks`) - failed probes aren't re-run.
- Per-provider tool-schema normalization so one schema works on every provider.
- `tools.native_tools` config (`auto`|`always`|`never`) with a settings dropdown.
- Live tool-call indicator (name + args) in chat and TUI while a call streams.
- E2e regression harness (`e2e-run.sh` + Windows parity) drives JSON scenarios headless; `--self-test` checks the assertion engine offline.
- Compressed tool results carry a recovery pointer to fetch the full output via the memory tool.
- HTTP responses over 64 KB are saved to a `.refio_http_*.txt` file with a `read_file` pointer instead of flooding the context.
- "Change approach" nudge after 2+ failed edits of one file or 2+ back-to-back `run_code`/`run_terminal_command` failures.
- New model definitions: Anthropic `claude-sonnet-5`, `claude-mythos-5`, and Ollama cloud `minimax-m3`, `ornith` (9b/31b/35b/397b), `glm-5.2`, `kimi-k2.7-code`, `deepseek-v4-pro`/`-flash`, `step-3.7-flash`, `nemotron-3-ultra`/`-nano-omni`, `mimo-v2.5-pro`.
- E2e stabilization gate (`cli --gate`): N runs per scenario -> pass-rate verdict, per-scenario baselines, trend history, `metrics.failureMarker` in `run.json`.
- Per-model/per-tool benchmark stats (`e2e-stats.sh`): model leaderboard, scenario-by-model matrix, tool-use histogram.
- App-build and CTF e2e scenarios - build tasks gated by a real `build_cmd` (or Playwright smoke test), CTF gated on a flag string.
- CI enforces full Gradle `check` on every PR, plus a Plugin Verifier job, a nightly workflow, and a tag-triggered release workflow.
- "Generate missing embeddings" button in the RAG panel (shown only when embeddings are missing).

### Changed

- Legacy plan/step path removed - PLAN/AGENT/headless all run through one `AgentTurnLoop` (headless now executes tools and writes files).
- Headless turns stream, fixing slow/cold local models killed at the socket-idle timeout (`qwen3.5:4b/9b` now complete).
- Stream idle-timeout ceiling raised 300 s -> 600 s.
- Small qwen3.5 models (`0.8b`/`2b`/`4b`/`9b`) use native tool-calling in `auto` mode, degrading to JSON if unsupported.
- Truncated-response detection unified into the extraction layer.
- High-frequency internal logs demoted to trace for a readable "Debug Logs" view.
- Agentic search is default; vector RAG opt-in (`rag.index_on_startup: false`). `grep_search` ranks declarations above usages.
- Subagent depth ceiling enforced before spawn - `invoke_subagent` refuses a call past depth 3 without allocating a child turn.
- Native-tools demotion needs two consecutive JSON-in-text slips (one slip retries on JSON for that turn only).
- RAG indexing/embedding moved off `Dispatchers.IO` onto a dedicated bounded pool.
- `llm_call` description clarifies it's the way to run an LLM step, not OpenAI/Anthropic HTTP from `run_code` (which has no keys).
- Guardian re-entry budget is per stall-episode: 3+ new tool calls after a re-entry replenish the single bounded re-entry.
- Headless `--model X` applies to every slot (turn, editing, plan, subagents) by folding into `ui.selected_model`; explicit `--config ui.selected_model` still wins.
- Internal decomposition (behavior-preserving) of the turn executor, project analyzer (2050 -> 783 LOC), and context service (1606 -> 1191 LOC).
- Chat input/panels use IDE icons and scale-aware sizing instead of emoji and fixed pixels (HiDPI-correct).
- Diagnostic/settings panels reworked: sortable Debug tables, incremental Logs/API-Logs updates, paginated API Logs, lazy tab init, standard IDE toolbars for Prompts/Subagents.

### Fixed

- Ollama streaming no longer discards a captured tool call when the `done:true` sentinel is missing.
- Native tool schemas counted in the context budget, so AGENT prompts don't overflow `num_ctx` on Ollama.
- API-log `source`/`taskId` reflect the actual request (PLAN vs AGENT now distinct), not the pool entry's first request.
- Bracket-labeled tool batches emitted as text (`[TOOL] read_file: ...`) are recovered, not dropped.
- Paraphrase-stall detection: word-level near-duplicate intent sentences now trip the repetition abort.
- Stray tool-call JSON no longer leaks into chat bubbles for non-native models.
- Stalled turns on weak native-tool models retry on the JSON-in-text path.
- Mid-turn reload no longer drops unpersisted bubbles or blinks out the prompt/streaming bubbles.
- Answers returned only in `reasoning_content` (some GLM/Z.AI/DeepSeek) no longer produce a blank reply.
- `run_terminal_command`/`run_code` decode output as UTF-8 (+ `PYTHONUTF8`, PowerShell prefix), so non-ASCII output isn't garbled.
- A plain-prose answer in JSON-envelope mode is surfaced and marked INCOMPLETE instead of lost as FAILED.
- `llm_call` returning empty is an error, not a silently-saved empty file.
- OpenAI-compatible adapter cost computed from the pricing table instead of hardcoded zero.
- Context panels render HTML lazily and capped, no longer freezing the UI on large tool results.
- `advance_code_editing` accepts only a fenced block; a fence-less reply goes through edit-repair then fails loud, file untouched.
- `multi_edit` validates all edits before writing, so a later failure doesn't leave earlier files half-written.
- A streamed call isn't retried once chunks reached the UI, so a mid-stream failure doesn't replay onto the partial.
- Embeddings cache key uses a fresh `MessageDigest` per call (the shared digest could poison the cache under concurrency).
- A turn resumed after a tool-approval pause yields the SQLite write lock to RAG indexing, like a normal turn.
- Single non-streaming write tools (`write_file`, `multi_edit`, overwrites) are snapshotted before execution for rollback.
- Tool success tracked via a structural flag, not an `Error:` text prefix, so legit `Error:` output isn't miscounted as failure.
- Execution step cards keep natural height with a status stripe (no "green wall"); "Delete All" moved to the toolbar's right edge.
- Session History uses toggle-button filters (no dead band) and compact token counts (2.4M/16.4K) with exact value in the tooltip.
- RAG file paths ellipsize in the middle (filename kept), full path in tooltip.
- Assistant bubbles strip echoed tool-result markers (`[Tool result: ...]`, "File created successfully:").
- File names with `<` or `&` are HTML-escaped, no longer corrupting bubble render.
- Tool-bubble status comes from the result flag, not a " failed"/"error:" substring, avoiding false red status.
- Large tool-call parameter values shown in an expandable panel instead of a 60-char truncated label.
- RAG "Search Test" results are clickable file paths (collapsed by default) instead of a debug dump.

### Security

- Command sandbox: a line with a chaining/substitution/redirection operator (`;`, `&`, `|`, backtick, `$(...)`, `>`, `<`, newline) falls from `ALLOW` to `ASK` so the whole line is reviewed (`git status; rm -rf /` no longer auto-runs). `BLOCK` still wins.
- No-egress gate no longer fails open - a config read error falls back to the last good value, else fails closed.
- SSRF guard validates every resolved DNS record, blocking the mixed public/private rebinding pattern.
- `run_process_background` defaults to `OFF` in PLAN and `ASK` in AGENT (was `ON`).
- `http_request`/`fetch_webpage` re-check SSRF/network-policy on every redirect hop, blocking a 30x to a loopback/internal address.
- `git clean` force flag blocked in any order/grouping (`-xfd`, `-d -x -f`, `--force`).

## [0.0.1.11] - 2026-05-31

### Added

- `INCOMPLETE` task status for turns that stop without delivering (guardian gives up, no-op-write / read-spree abort), with its own TUI colour and a `◐ Incomplete` History badge.
- Read-spree consolidation nudge - after many reads with no write, a SYSTEM nudge to persist to memory or deliver (top-level AGENT).
- No-op-write streak abort - a WRITE changing nothing 3x on the same target aborts the turn as `INCOMPLETE`.
- `ConsecutiveTextRepetitionTracker` aborts on byte-identical final text repeated with no tool call.
- MCP `GLOBAL`/`TOOLS` servers register `mcp_<server>_*` tools into every registry (context7 preset switched to `TOOLS`); WARN when a server exposes none.
- `ModelWindow` single context-window resolver (replaces four that disagreed); `claude-opus-4-8` added.

### Changed

- Next-speaker judge runs in PLAN too and passes the tool-use count (not just distinct names); recovers a stashed answer on an empty native reply.
- Loop detectors exempt pure-symbol runs (ASCII diagrams, `────`/`====` rules).
- LLM stream idle-timeout clamped to 300 s, independent of the total timeout, so a dead stream fails fast.
- Ollama `num_predict` clamped so input + output fits `num_ctx` (overflow warning); a streaming watchdog makes Stop respond immediately.
- RAG: duplicate/contained chunks dropped at chunk time and in `rag_search`; line bounds clamped; inserts batched and the turn yields the WAL writer-lock.
- Context-panel auto-refresh fires only on session change (was every ~1.5 s); sections refresh on demand. Removed unused `TurnLoopConfig.loop*` fields.

### Fixed

- Output-repetition abort was defeated by the loop nudge's varying `subtask_id`; `loopSignature` now feeds the tracker raw output.
- Rejected-tool bubble flips to "✗ Failed" immediately, not after the post-turn DB reload.
- Guardian re-entry nudges render as a gentle "Agent guidance" note instead of a raw "STOP" wall.

## [0.0.1.10] - 2026-05-27

### Added

- `/goal <condition>` command sets a completion condition the judge re-enters the loop until met; TUI + IntelliJ chat, persists across restarts.
- LLM "next speaker" judge in AGENT: after a tool-call-free reply a weak-model call decides finished vs stopped, re-entering with a nudge (max 3). Toggle `general.next_speaker_judge_enabled`; fails to "pass".
- Content-chanting loop detection aborts on the same word n-gram repeated 10+ times consecutively.
- Anthropic prompt-prefix caching (stable/volatile split, 5-min TTL); cache tokens folded into reported `inputTokens`.
- Multi-agent A2A messaging: per-agent queues; `send_message` enqueues to a peer, `answer_message` replies to a specific message.
- Native function calling: per-provider test suites lock the wire format; tool-call extraction robustness fixes.
- Universal `<tool_use_enforcement>` block in the system prompts (replaces `ModelFamilyClassifier` injection); new `<task_planning>` block.
- PLAN iteration cap raised 50 -> 100 (warning at 30), matching AGENT.
- `EmbeddingCircuitBreaker` for embedding-provider failures.
- `CodeIntelligenceTool`, `GrepSearchTool`, `ReadFileTool`, `ReadDirectoryTool` - expanded actions, better formatting and token budgeting.
- `WebSearchTool`, `FetchWebpageTool`, `HttpRequestTool` - refined error handling and network-policy integration.

### Changed

- `TurnGuardrails` simplified to objectively-broken triggers only (empty envelope, native-text tool call, malformed JSON, output-hash repeat); prose-pattern detectors removed.
- Format-retry fires only on objectively broken output, not on legitimate plain-text answers in native-tools mode.
- `ModelFamilyClassifier` removed (replaced by the universal `<tool_use_enforcement>` block).

### Fixed

- `MultiAgentRunner` agent-instance-ID propagation edge cases.
- `ChatService`/`ContextService` refactors and fixes.
- `SubtaskTracker` lifecycle accuracy.

---

## [0.0.1.9] - 2026-05-05

### Added

- `DiffCompressor` elides tool-result diff bodies (recovery hint embeds `subtask_id`), saving ~8-14K tokens after a write.
- Centralized LLM stats in `LLMClient` - tokens/cost auto-incremented on `task`/`subtask` rows, not per call-site.
- Persistent native-tools fallback (`models.native_tools_fallbacks`) so the probe cost isn't paid every fresh process.
- Native function calling for OpenAI-compatible providers (OpenRouter, Z.AI, Generic OpenAI, LM Studio).
- Sub-LLM cost surfaced in tool-call bubbles (`advance_code_editing`, `multi_line_editor`, `fetch_webpage`, ...).
- `maxContextWindow` cached as a `StateFlow`, off the EDT/SQLite path.

### Changed

- AGENT task status transitions `NEW -> RUNNING -> SUCCESS/FAILED` (was stuck at `NEW`).
- Token-only session refresh skips 5 redundant `ConfigRepository` writes per turn.
- `SessionStatsCalculator` prefers the `task` row over per-message summing, fixing header/footer drift.
- `MessageDispatcher` re-attaches tokens from dropped messages to the last visible message.

### Fixed

- `OpenAIAdapter` no longer drops `prompt_tokens`/`completion_tokens` from the final usage block.
- Removed dead `SubtaskRepository.updateLlmMetrics` (kept the additive `incrementLlmMetrics`).
- Header/footer token drift after auto-name or thinking-only turns.

---

## [0.0.1.8] - 2026-04-27

### Added

- **Native function calling** - structured `tools` API instead of JSON-in-text; opt-in via `tools.native_tools: auto|always|never` (default `auto`). Covers OpenAI (Chat + Responses), Anthropic, and Gemini with per-provider schema sanitization.
- `ToolSchemaSanitizer` - provider-aware JSON Schema normalization (OpenAI strict, Anthropic keyword stripping, Gemini coercion).
- `NativeToolsFallbackTracker` - on a 400 "tools not supported", the model skips native tools for the rest of the process.
- `NativeToolsResolver` central decision logic (precedence: fallback cache -> NEVER -> ALWAYS -> `supportsFunctionCalling`).
- `ToolCallParser` XML-tag fallback recovers `<tool_name><arg>...</arg></tool_name>` pseudo-tags from weak local models.
- New models: GPT-5.5, GPT-5.4 Nano, Qwen 3.6 35B/27B, Qwen 3-next 80B, gpt-oss-safeguard 20B/120B, OpenRouter `qwen/*`.
- `claude-sonnet-4-6` added to `SupportedModels`.
- Per-iteration token/cost persisted to the task row for running totals without aggregation.

### Changed

- `AgentTurnLoop` auto-falls back to JSON-in-text on `ToolsNotSupportedException`, rebuilding the prompt.
- With native tools active, the `json_object` response-format override is suppressed.
- `TurnLLMCaller` forwards `nativeToolSchemas`; retry handler propagates `thinking`, `reasoningEffort`, `noEgressEnabled`.
- Subagent profiles filter the native `tools` array to their `<available_tools>` list.
- `ToolCallParser` `startsWith("{")` pre-guard removed (handles prose before the envelope); failed-parse log downgraded to `DEBUG`.

### Fixed

- Anthropic tool results mapped to `role: "user"` (was `"assistant"`), fixing HTTP 400 on claude-opus-4-6+.
- `temperature` no longer sent to models that deprecated it (`claude-opus-4-7`/`-4-6`, `gpt-5.5`, `gpt-5.4-nano`).
- Anthropic streaming captures the `tool_use` call `id` alongside the name.

---

## [0.0.1.7] - 2025-04-17

### Added

- New agent tools: `sleep`, `ask_user`, `web_search`, `fetch_webpage`, `run_process_background` + `monitor_process`, `code_intelligence`.
- Subagent overrides - customize built-in subagents locally without forking.
- Example `config.yaml` shipped under `core/src/main/resources/config/`.

### Changed

- Execution mode, thinking, and no-egress toggles moved to Settings -> General.
- `thinkingEnabled`/`noEgressEnabled`/`executionMode` moved from `ui:` to `general:` (old `ui:` values ignored).
- "Slash Commands" renamed "Prompts" (`/name` syntax unchanged, entries migrated).
- Terminal command rules unified into a single regex-based `ALLOW`/`BLOCK`/`ASK` ruleset.
- Models tab no longer stalls on open (discovery runs only via Refresh).
- Local provider listing timeouts tightened (Ollama/LM Studio: 3 s, was 15 s).
- Tools settings tab is responsive; YAML config is now typed (unknown keys raise errors).
- Session layer shared between plugin and CLI; Settings panels rebuilt on the typed config model.
- Built-in `system-agent` prompt refreshed.

### Removed

- "Enable No-Egress by default" checkbox removed (duplicated the General toggle).
- Multi-agent orchestration UI and strategies removed (main agent delegates via `invoke_subagent`).
- Various internal backcompat shims.

### Fixed

- UI no longer freezes while persisting session state.
- Silent fallbacks removed from OpenAI parsing, terminal-rule compilation, and `http_request`.
- Duplicate agent-turn dispatch wiring consolidated.

---

## [0.0.1.6] - 2025-04-12

### Added

- Tool approvals with `ASK` permission and session trust; clearer prompts in IntelliJ and CLI.
- Follow-up messages can be queued while the agent is still working.
- Terminal command rules (per-pattern `ALLOW` / `BLOCK` / `ASK`).
- `delegate_to_strong_model` tool + optional `STRONG` model slot for heavy tasks.
- Compact prompt mode for smaller-context models.
- Multimodal pipeline (image-aware prompts) and broader MCP support (prompts, resources, cached metadata).
- Early multi-agent runtime: parallel orchestration, graph/timeline panels, nested-run visibility.
- Onboarding guide in `docs/onboarding.md` and broader automated coverage.

### Changed

- Tool permissions fully support `ON` / `ASK` / `OFF`; terminal in AGENT defaults to confirm.
- Agent turns recover better from rejections, transient HTTP issues, and repetitive tool loops.
- `run_terminal_command`, `run_code`, `http_request` more reliable around timeouts and retries.
- Read-before-write / verify-after-write enforced more strictly in prompts and tool guidance.
- RAG, context budgeting, and working-memory decay retuned; system-agent prompt condensed.
- RECENT_WORK is budget-driven (FULL -> DETAILED -> SUMMARY) and includes failed tool calls.

### Fixed

- `http_request` egress checks, regex safety, approval/trust race conditions.
- Subagent recursion tracking, `CoreApiRouter` shutdown cleanup, file-write locking.
- Image/PDF handling in `read_file` and multimodal provider payloads.
- Chat message ordering when streaming and tool output interleave.
- Weak models silently ending a turn without the JSON envelope are nudged back to format.

---

## [0.0.1.5] - 2025-04-02

### Added

- **Standalone CLI** with full TUI (Mordant + JLine3): tabs, autocomplete, session history.
- **Multi-agent architecture**: parallel orchestration, event bus, YAML task definitions.
- New tools: `http_request` (with `save_to_file`) and `run_code` (Python, JS, Kotlin Script).
- 7 standalone context providers for CLI mode.
- File-based prompt registry (project/user/built-in) with Markdown definitions.
- Turn-state inspector: prompt snapshots, context traces, token usage.

### Changed

- Gradle split into `:core`, `:intellij-plugin`, `:cli`. Core no longer depends on IntelliJ at compile time.
- `ContextService` split into formatter/pruner; conversation compaction uses structured summaries.
- Working memory decays stale entries; tool-result persistence keeps up to 16 KB before summarizing.
- `PromptsService` / `SubagentRegistry` moved onto the shared layered registry.

### Fixed

- TUI: `Ctrl+D` crash, duplicate streaming messages, JLine dumb-terminal warnings.
- `PathSandbox` symlink resolution on macOS; MCP disabled/stale/auth-required state tracking.
- Plan bubbles show real tool calls; conversation compaction preserves structured summaries.

### Removed

- Compose Desktop GUI (replaced by TUI).

---

## [0.0.1.4] - 2025-03-22

### Added

- Project instructions via `.refio/agent.md`, `AGENTS.md`, and `.refio/rules/*.md`.
- Richer project analysis for CSS, TypeScript, HTML, C++, and dependency ecosystems.
- Typed config access via `ConfigKeys` + `getTyped()` / `setTyped()`.
- HTTP API server exposing `CoreApiRouter` over JSON and SSE.

### Changed

- Terminal command policies relaxed for normal dev workflows; destructive actions stay guarded.
- Command timeouts and output limits increased for longer builds and tests.
- Subagent execution aligned with the newer turn-loop and JSON planning flow.

### Fixed

- Nested JSON model responses unwrapped more reliably.
- Chat tool bubbles stable after streaming; subagent responses show clearer attribution.

### Removed

- Legacy multi-step subagent execution path (replaced by the current turn loop).

---

## [0.0.1.3] - 2025-03-18

### Added

- Dedicated `providers.custom_openai` support (OpenAI-compatible backends) and standalone Z.AI provider with its own Settings card.
- `security.allow_symlinks` opt-in for symlink access in `PathSandbox`.
- `PRIVACY.md` describing local storage, cloud behavior, no-egress mode, and secret handling.

### Changed

- Model discovery uses single-flight caching; token estimation no longer fetches provider model lists on the hot path.
- Tool-call bubbles preserve assistant narrative alongside tool metadata.
- RAG: configurable memory controls, semantic chunking, paginated embedding scans, result caching for `@codebase`.
- Embedding generation batched for OpenAI and Ollama.

### Fixed

- `ChatView` row-layout fix for regular chat bubbles.
- PLAN/AGENT now forward `thinking` and `noEgressEnabled` to `LLMClient.complete()`.
- `PathSandbox` rejects symlinks by default (incl. parent dirs); `advance_code_editing` enforces excluded extensions.
- Streaming placeholders no longer leak raw `{"actions":...}` JSON during an active turn.
- Provider failures normalized into typed Refio LLM errors (timeout, auth, rate-limit, upstream).
- Ollama requests gated per endpoint to reduce contention.

---

## [0.0.1.2] - 2025-03-07

### Added

- Built-in slash command `/implementation-analysis`.

### Fixed

- Longer allowed AGENT/PLAN runs; deeper read-only analysis before nudging toward writes.
- Empty structured responses (`{}`, `""`) retry instead of ending the turn prematurely.
- Local providers (Ollama/LM Studio) no longer receive forced `json_object` response format.
- Deterministic fallback when the weak model returns an empty summary.
- AGENT prompt trimmed; `thinking` is optional, JSON contract kept explicit.

---

## [0.0.1.1] - 2025-03-03

### Fixed

- Terminal warning on IntelliJ check.

---

## [0.0.1] - 2025-03-02

Initial release - a local-first AI coding assistant for IntelliJ IDEA.

Highlights:

- **Three execution modes** - CHAT (conversational), PLAN (read-only analysis), AGENT (full read/write), all backed by a Codex-style `AgentTurnLoop` with subtask tracking and safety limits.
- **10 tools** (5 read-only, 5 write) including `multi_line_editor` and `advance_code_editing`; `run_terminal_command` disabled by default.
- **18 context providers** via `@file`, `@folder`, `@current`, `@codebase`, `@grep`, `@commit`, `@docs`, `@url`, and more.
- **RAG with local embeddings** (Ollama + OpenAI), hybrid semantic+keyword search, incremental indexing.
- **6 LLM providers** - Ollama, OpenAI, Anthropic, Gemini, OpenRouter, LM Studio.
- **MCP protocol** support (stdio + HTTP/SSE) with 16 built-in server presets.
- **Subagents** with YAML frontmatter (Claude Code compatible), built-in `security-reviewer` and `code-reviewer`.
- **Security layers** - `PathSandbox`, `FileLimits`, `CommandDenylist`, per-mode tool permissions, no-egress mode, secret redaction.
- **Config hierarchy** - DB (Settings UI) > project `.refio/config.yaml` > user `~/.refio/config.yaml` > built-in defaults.
- **SQLite persistence** via Exposed ORM (sessions, chat history, snapshots, API logs, RAG index).
- Native IntelliJ Swing UI with chat view, `@` autocomplete, context panel, and 12+ settings panels.

See `docs/` for full architecture details.
