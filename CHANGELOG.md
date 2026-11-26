# Changelog

All notable changes to Refio are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added

- Refio as an agent in JetBrains AI Assistant and Zed: `refio --acp` speaks the Agent Client Protocol (v1) over stdin/stdout. The IDE starts it for the open project; it reports tool calls with file diffs, asks for approval in the IDE (allow once, always allow the tool, reject the call, reject and stop), honours the IDE's stop button and switches between Agent, Plan and Chat. The plugin action **Tools > Register Refio in AI Assistant** adds it to `~/.jetbrains/acp.json` without touching other agents.
- New models with prices, windows and descriptions taken from models.dev (2026-11-20): GPT-6.2 Astra and Sol, GPT-6.1 Terra, Claude Fable 5.2 and 5.1, Claude Haiku 5.5, Gemini 4 Neon, Hydrogen, Carbon and Argon, Gemini 3.8, 3.7 and 3.6 Flash, Gemini 3.5 Flash Lite and the free GLM-4.6V Flash. All of them show up in the model picker. Claude Haiku 5.5 gets its thinking depth as an effort level, like the rest of the Claude 5 family.
- 33 new e2e scenarios (wave 6) for security and operations work: reconnaissance in six languages, vulnerability triage (SQL injection variants, SCA findings, sanitizer crash reports), red-team exploitation of live local targets (IDOR, race, prototype pollution, stack overflow), white-team hardening (crypto migration, IAM least privilege, security headers), decompilation (javap, pyc), debugging, Linux operations, multi-agent collaboration and benchmark-style tasks (Terminal-Bench, AgentBench-OS, Cybench). Every scenario has a golden solution, an untouched fixture that fails and 4 to 6 negative controls.
- 24 new e2e scenarios (wave 7) in the Terminal-Bench (tbench.ai) style, all cybersecurity and operations: log and config forensics (nginx error budget, gid inventory, sshd Match semantics, DHCP reservations, apt pinning), PKI drills (TLS chain verification, CA CRL publishing, cert minting plus revocation, SHA-1 to SHA-256 PKI migration), git history sanitisation, defensive triage (sudoers typo, rogue udev rule, pcap baseline, phishing sender analysis, MITM handshake timing, Zigbee sniffing, SIEM threshold tuning, scattered credential redaction) and server-side security fixes against live local targets (SSRF redirect bypass, shadowed routes, 2FA state reset, stale vhost takeover, secret rotation, domain hijack restore). Every scenario has a golden solution, an untouched fixture that fails and 4 to 5 negative controls.
- More e2e scenarios (the suite now has 382), including ones that check how the agent plans longer tasks, and better scenario selection and reporting in the e2e harness.

### Fixed

- When the provider refused a request (finish reason `refusal`, seen on Claude for a defensive CTF task), Refio took it for an empty reply and asked the model up to four more times to fix its format, paying for each refused retry, and then advised switching to "a more reliable model". The turn now ends at the first refusal with stop reason `MODEL_REFUSED` and says the model declined; over ACP the IDE gets the `refusal` stop reason.
- Fixed e2e scenarios that failed correct solutions.
- A tool name with spaces around it (`" tasks "`, sent by qwen3.5) was rejected as an unknown tool, so the call was lost. Names are now trimmed before the tool is looked up.
- In `run.json` a tool call that parsed but then failed (unknown tool, tool error) was marked `ok: true`. It is now `ok: false` with the error text.
- The CLI could pick up the plugin's logging setup, which writes to stdout, the channel the CLI keeps for `run.json` and the ACP protocol; which setup won depended on the order of jars. That setup now ships only with the plugin, and the CLI logs to its file alone.
- When Refio stopped a model looping on checks after it had edited a file, it reported the turn as a success without running the project's tests, even when they still failed. It now runs the check first: the turn is a success only when the check passes or the project has none, and the answer says when the change was not tested.
- On Windows, a file the agent wrote through the terminal (`>`, `Out-File`, `Set-Content`) came out as UTF-16, which Python and Node cannot read. The terminal now writes files as UTF-8.
- Claude Fable 5, Gemini 3.5 Flash, Gemini 3.1 Pro Preview, Gemini 3.1 Flash Lite and Gemini 3 Flash Preview had definitions but never showed up in the model picker. They do now.
- `--config tools.permissions=<json>` was ignored (`--print-config` showed it applied, but permissions came from the database alone), so a run meant to switch a tool off ran with it on. The named tools now override stored permissions for that run (`{"tools":{...}}` and a bare map both work), and a malformed value stops the run.
- On Windows, double quotes in a terminal command were lost on the way to PowerShell (`Write-Output "a|b"`, quoted paths). Arguments are now escaped the way Windows splits a command line.
- The post-turn check ran its command in `cmd.exe` while the agent's terminal uses PowerShell, so `./gradlew build` failed in the check after working for the agent. Both now use the same shell.
- The post-turn check could hang for hours when the command left a background process (such as a Gradle daemon) holding its output. It now kills the processes it started once the output wait runs out.
- With more than ten pinned memory notes, the note pushed out was random when several were written within one clock tick (common on Windows). The earliest task step's note now goes first.
- The context preview in the TUI panels and the IDE's Debug and Context panels no longer calls MCP tools. Each 30-second refresh re-queried a context-mode server (for example context7), so an idle TUI with the API tab open made a request every half minute. The preview now shows what the last real turn fetched; turns are unchanged.

## [0.0.2.2] - 2026-11-09

### Added

- `agent.prompt_layout` (`trailing` by default, `system`) decides where a top-level PLAN/AGENT turn sends its per-iteration work state. With `system`, working memory and recent work sit mid-system-prompt and change every iteration, so a local server's prompt cache (Ollama) re-reads everything after them. `trailing` moves them, plus a copy of the task requirements, into a final `<system-reminder>` message rebuilt each iteration and never stored, so only the tail is re-read. Subagents always use `system`, because their context profile filters the system prompt.
- Long tasks: one message runs in rounds of AGENT turns until a command exits 0 in the project (`/until <cmd>` in the TUI and IDE, `--until "<cmd>"` headless) or the completion judge finds a goal met (`/goal <condition>` plus `/long on`, `--goal "<condition>"`). Limits: `long_task.max_rounds` (12), `long_task.max_minutes` (240), `long_task.max_no_progress_rounds` (3), `long_task.check_timeout_seconds` (600); `/long rounds N`, `/long hours H`, `/long off` and `/until off` apply from the next round. `--resume <taskId>` continues a stopped task (one that ended on its round or time limit gets the same allowance again), `.refio/progress.md` (`long_task.progress_file`) shows rounds, plan and next step, and `run.json` lists each round under `longTask`.
- Handoff note: a turn that stops on its time or iteration limit or a guardrail makes one more tool-less model call and stores what is done, what is left, the next step, files and problems; the next turn, round or message starts from it, and a subagent's note goes into its answer. A turn stopped by the cost limit skips it. `agent.handoff_note` (`true`), `agent.handoff_note_timeout_seconds` (120).
- Decision model for the completion judge: `general.judge_backend` (`llm` by default, `decision`, `hybrid`). With `decision`, the model in `default_model.decision` answers three yes/no questions (deliverable missing, reply only announces the next step, reply claims a result no tool produced) in about a second; `hybrid` asks the weak chat model only when the probability falls inside `general.judge_hybrid_band_low` / `_high` (0.15 / 0.85). `general.judge_decision_threshold` (0.5) sets the cut-off. The weak model still judges without a decision model, on call failure, or while a `/goal` is active. Ollama models reporting the `decision` capability get their own slot in Settings -> Models (IDE) and the Models tab (CLI) and never fall back to the chat model.
- Multi-agent run limits in the `--multi-agent` YAML: `on_failure` (`skip` the dependents of an agent that did not complete, the default; `stop`; `continue`), `max_minutes`, `max_cost`, `max_parallel`, and per agent `profile`, `max_iterations`, `max_minutes`. A dependent agent's task gets each dependency's status, changed files and report. `run.json` carries each agent's status, files and conversation, and each subagent's details under `agents`.
- The model sees its turn budget: iterations and minutes used and left, with a warning at half, four fifths and nineteen twentieths of the nearer limit.
- `logs.retention_days` (7, `0` keeps everything): full model requests and answers (`api_logs`), agent events and rolled CLI logs older than that are deleted at start and every 12 hours. Debug-panel token and cost statistics cover the retention period only.
- `llm.retry_max_seconds` (300, `0` disables): the agent's model call is retried after a passing failure (connection refused, 5xx, 429) with pauses of 2, 4, 8, 16 and then 30 seconds while the server has been failing for less than this time, so a server restart costs a pause, not the round. Stop interrupts the pause.
- Tasks left behind by a process that stopped mid-work (closed IDE, Ctrl+C, crash) are repaired at start: the task becomes INCOMPLETE, a long task INTERRUPTED (resumable), running steps CANCELED, and every tool call of the interrupted step gets a result. Tasks whose owner process still runs are left alone.
- The memory tool's own notes are pinned: a separate pool, no loss of importance with age, pushed out only by newer notes of the model, never by automatic facts. The model is told when a pinned note made room.
- New e2e scenarios: `long-12-stubs` (a long task filling twelve stub functions until tests pass) and `multi-agent-handoff` (a dependent agent must use the measured value from the one before it).

### Changed

- The terminal UI was rebuilt. Layout follows the terminal width: from 180 columns a sidebar, chat and right column (STEPS over CONTEXT); from 120 chat and right column; from 80 one pane at a time; below 80x24 Refio says how much room it needs. F2-F8 open the panels (Steps, Context, RAG, Logs, Debug, API, Files), F9 the settings. A tool set to ASK shows its diff at the end of the chat: `y` once, `a` allow this tool in this folder for the session, `d` full diff, `n` say what to change. Ctrl+P / Ctrl+B select messages and tool calls (Space expands output, `o` opens all of it, `v` shows a whole message). `/continue`, `/summarize`, `/context`, `/plan`, `/model`, `/new`, `/effort`, `/help` are commands (a prompt template with the same name wins). An answer cut off at the output limit offers `c` continue, `s` split into steps, `l` raise the limit and continue, `m` switch model. `NO_COLOR=1` and `REFIO_ASCII=1` support plain terminals.
- The Context panel splits window fill into conversation, project analysis and overhead, and warns while the window is only the provider's default.
- Each agent run builds its prompt from its own steps, and the parent gets a subagent's work only through the `invoke_subagent` result. Files changed anywhere in the task stay visible to all. A migration assigns existing steps to their run (`subtasks.agent_instance_id`).
- `invoke_subagent` answers in a fixed shape: an outcome line, then changed files, open questions and the report. A subagent that stopped on a limit still hands over its work; only a stop on an error is a tool error, and files and report go with it.
- Subagents asked for in one reply run together only when that can help: in AGENT mode a subagent with a write tool waits for the previous one, and on Ollama they run sequentially unless `providers.ollama_max_concurrent` is above 1 (to match `OLLAMA_NUM_PARALLEL`).
- Messages the harness writes to steer the agent (guardian and format nudges, verification feedback, tool rejections, interjections, peer answers, the handoff note) reach the model as user messages prefixed with `[harness]`; as system rows, providers dropped them.
- The tool-error guardrail counts model replies, not calls: a reply fails only when none of its calls worked. A call denied by policy or the user is not a tool error.
- The main agent's plan (`tasks`) is stored with the task, so it survives the process.
- PLAN and AGENT system prompts require every reply to either make a useful tool call or deliver the complete final result.

### Fixed

- Conversation summaries are rolling: a new summary covers the previous one plus later rows, so the first summary's facts no longer vanish. The opening request and every user message stay verbatim, and the cut never separates a tool call from its result. The summary's output budget was raised (six mandatory sections were cut off at 500 tokens).
- Tokens and cost of a subagent or a `delegate_to_strong_model` call were counted twice in the task totals.
- A tool result shown whole no longer carries a hint to fetch the rest.
- The long task's end check and the build check kill the whole process tree on timeout or cancel and keep only the end of the output in memory, so a hung test cannot fill memory or leave a server holding its port.

- `grep_search` no longer loses a declaration when usages exceed the limit: declarations and usages use separate pools capped at `max_results`.
- An MCP server that forgot its session (HTTP 404 to a request carrying `Mcp-Session-Id`) is re-initialized once and the request repeated, instead of failing every call until reconnect.
- The MCP connection test in settings reads event-stream framed answers and, for SSE servers, the answer arriving over the stream.
- A legacy SSE MCP server (no announced endpoint) connects after about half a second instead of two.
- GLM models on Z.AI kept reasoning with thinking switched off (GLM 4.5+ think unless told otherwise), which could eat the whole output budget. Z.AI now always receives `thinking.type`, `disabled` or `enabled`.
- A response cut off by the output cap before any tool call ended the turn as if the model had finished. It is now reported as truncated and the turn fails.
- `run_code` could hang forever when the script started a surviving child process (the stdout pipe stayed open). Output is drained on its own thread, the process tree is tracked, a surviving child is killed after a grace period, and a timeout kills the tree and returns partial output.
- An empty tool result (a script that printed nothing) was dropped from history, and providers rejected the unanswered call (Ollama: "Cannot continue an assistant message that contains tool calls"). A result answering a call is now always kept.
- "invalid tool call arguments" from llama-server (a model split across a cluster behind Ollama) is recognised as a broken native tool template, so the turn falls back to the text format instead of repeating the rejected call.

## [0.0.2.1] - 2026-10-11

### Added

- The run document records what the turn loop actually did: iterations against the cap, why it stopped (a named reason per exit), guardrail firing counts, the context budget and its use, files written and whether content changed, and whether the native tool channel was given up mid-turn. Tool calls carry success and error. Before, a capped turn, a repetition-guard stop and a clean finish all reported the same way.
- `general.execution_isolation` (`auto` by default, `required`, `off`) decides how model-authored code may run. `required` refuses code the model just wrote, while commands the project's own toolchain runs still work. No sandbox backend ships: `auto` behaves as before and logs that once. The terminal tool and MCP child processes use the same check.
- `agent.max_format_nudges` (default 2) sets how many reminders a turn spends on a model that keeps answering in the wrong shape; it was a constant.
- The native tool channel can be decided from the server, not only the built-in registry: a locally built model is absent from it, so `auto` assumed no function calling. Ollama is now asked what the model supports, once per model, and the answer is cached for the process.
- The Debug screen shows the model the session last actually used, which is not always the one in the dropdown.
- The scenario harness gained configuration overrides carried by the scenario (a command-line override still wins), an assertion that a text does NOT appear in the output, and one that a file was NOT created. It reads the iteration count from the run document instead of counting assistant messages.
- The MCP client speaks the transport as the specification defines it: a `text/event-stream` response is read event by event and the reply matching the request id is picked out; an SSE server announcing its endpoint with an `endpoint` event gets its POSTs there; `Mcp-Session-Id` is taken from the `initialize` response, sent on every later request and dropped on reconnect. A spec-compliant server used to fail on the first framed response; Refio-dialect servers connect as before.
- `metrics.verification` in the run document tells apart what the verification command did: `exitCode`, `timedOut`, `baseline` (the same command on the untouched project before the first write), `attributionUncertain` (already failing before the turn) and `notRunReason`. `ran`, `attempts` and `result` keep their meaning.
- The scenario harness writes a `manifest.jsonl` next to each batch's results: commit, scenario hashes, model, Ollama model digest, quantization and template, context window, hardware, and the effective configuration with secrets masked. Anything not found is `null` with a reason and never fails the run. Results link to it through a `batch` field.
- `assert.preserved_except` checks that a file is unchanged outside an allowed region, or a JSON document is identical once allowed paths are removed. `large-file-edit` and `add-build-script` use it.
- Deliberately wrong solutions live under `test_data/e2e/negative/<id>/` and the validator requires each to be rejected: a truncated file, a changed neighbouring function, a deleted test, an edited test script, a constant answer.
- Six new multi-file scenarios aimed at local models (finding the active validator among 600 archived copies, a bug next to an unrelated failing test, a CSV report contract over several modules, a Kotlin configuration-precedence fix, an offline JSONL report, driving a local CLI from its documentation). Two scenarios keep MCP calls covered in the Refio dialect.
- `modelRanBuildCmd` in the harness results counts terminal commands where the model itself ran the scenario's build command, apart from `self_verified` (Refio's loop verifier).
- GPT-6 (Astra, Sol, Luna) and GPT-6.1 Sol on OpenAI, Claude Opus 5.5, Opus 5 and Sonnet 5.5 on Anthropic, the GLM-5.3 family (`glm-5.3`, `glm-5.3-flash`, `glm-5.3-flashx`, `glm-4.6v-flashx`) on Z.AI, and Opus 5.5 plus Gemini 3.8 Flash on OpenRouter, each with its own context window, output cap and published prices.
- The benchmark sweep pins and records Refio's reasoning level: `--thinking-level low|medium|high`, applied as a run-scope `general.reasoning_effort` override.
- The benchmark viewer has an Overview page: pass rate, scores, cost and time per model, per task, and as a model-by-task matrix, over reviewed runs, queue runs or both.
- The leaderboard has a Refio Score: one 0-1 figure per model blending the human score (two thirds) with the judges' score (one third, or the human score alone when no judge scored), scaled by stability across attempts. Stability takes away at most a fifth and never adds; a row with no stability measurement has no score.
- A header switch shows the Refio Score as is or against the leader: each task is scored against the best result any model reached, so a hard task lowers nobody who did as well as possible, and the leader is 100%.
- A second header switch limits every page to locally run models. Local viability is still measured against the best cloud run, so cloud runs are dropped after the leaderboard is built. The filters' "Clear" button leaves it alone.
- The benchmark viewer is available in English and Polish (header switch), including date pickers and the help page.
- The leaderboard opens on the three quality scores; other columns sit behind a view switch (scores, reliability, speed and cost, or all). View, language, Refio Score mode and local-only switch are remembered in the browser.
- `npm run judge` takes `--concurrency <n>` to judge several results at once (`results.json` writes are queued). `--re-judge-since <ISO date>` resumes an interrupted re-judge, redoing only older or failed verdicts.

### Changed

- The CLI log rolls (50 MB per file, 7 days, 500 MB total) and appends on start, so the failed run's log survives the next start.
- The reminder budget for a malformed answer doubles while the turn has produced nothing.
- A turn abandoned because the model kept repeating itself is now reported as incomplete, not successful. This is a behaviour change for task status and anything reading the run.
- The agent execution view subscribes once to a stream that replays stored history and then continues live.
- A verification command failing both before and after the turn is reported as run and failed with `attributionUncertain`, not as not run. The turn is not failed and no repair round starts, so the agent is not pushed to fix a breakage it did not cause. A command that cannot start is reported as skipped with a reason instead of raising an exception.
- A repetition abort on a read-only tool tells the model the search or read keeps returning the same result and to change the query or act; it used to claim edits did not change runtime behaviour.
- When the approval gate refuses to delete a file in the project, the refusal suggests rewriting it with `advance_code_editing`; a model trying `rm`, `rm -f` and `unlink` in turn ended the turn on repeated denials.
- The `multi-file-notes-api-hidden-tests` scenario describes its tests as visible, protected acceptance tests; the id is kept so older results line up.
- Claude models from Opus 4.7 on, and the whole Claude 5 family, are sent a reasoning effort instead of an extended-thinking token budget, which they reject (also for Bedrock-prefixed ids).
- A model that reasons unless told otherwise (Opus 5.5, Opus 5, Sonnet 5.5, Gemini 3.8 Flash, Kimi K3) runs at its lowest effort when reasoning is OFF, on Anthropic and OpenRouter; the toggle was a silent no-op for them.
- A reasoning model reached through OpenRouter now accepts the thinking toggle; its definitions declared no thinking support.
- An Anthropic cache write is billed at 1.25x the input rate (the five-minute cache price), not the plain input rate.
- The benchmark scores every criterion on one 0-6 scale (0 missing, 2 partial, 3 works with clear defects, 4 good, 5 very good, 6 exceptional) instead of 0 / 0.5 / 1 and a 0-2 look scale. Existing scores were rescaled with the old top mark at 4 (only the old "excellent" look became 6), so old results are two thirds of their former value and the judge ranking is unchanged. The pass threshold moved from 70% to 50% (3 of 6), score colours change every 25%, the human-versus-judge divergence badge is 2 points, and the deterministic queue check awards 0, 2 or 4.
- Every benchmark task carries `judgeInstructions`: how the LLM judge should score it. All 25 tasks have one, editable in the task editor; the model under test never sees it. The judge gives a reason for every score and keeps 6 for work beyond the task.
- Editing a benchmark result in the admin view shows scores and notes at the top, the live artifact and judges' scores with reasons below, and folds task, model, metrics and attachments into "Run details". A score reads as number and meaning ("4 - good").
- A benchmark model records what its weights are: quantization, dense or mixture-of-experts with active parameters, longest context, capabilities, license, size on disk and weights digest, shown under each model name on the leaderboard ("Q4_K_M · MoE A3B · 256k ctx · 23.9 GB"). Local models were filled in from the Ollama server, which corrected both gpt-oss models to MXFP4 (previously Q4).
- Every benchmark attempt on a local model records what Ollama served: weights digest, server version and baked-in sampling defaults (temperature, top_p, top_k, stop sequences), plus after each attempt the memory taken, GPU share and context window actually loaded. A GPU share below 1 marks a partly-CPU attempt whose timing is not comparable. Anything the server does not answer is left out; earlier attempts have none of these fields.
- A benchmark model also records release date, knowledge cutoff and list price per million input and output tokens. Prices of the 17 Anthropic, OpenAI and Z.AI models came from Refio's model definitions; OpenRouter models stay empty (prices are read live) and no release dates or cutoffs were filled in.
- Editing a benchmark result no longer runs the artifact on open (model-written HTML can hang the browser); it starts after "Run artifact" is pressed.

### Fixed

- The benchmark harness could not measure an external coding agent on this machine, and each of four causes was recorded as an ordinary failed attempt instead of a broken measurement.
  - A deliverable's file name was read by splitting on a forward slash only, so on Windows the whole absolute path became the name and copying failed (which is why no multi-file task had ever landed in the queue).
  - The run deadline did not stop the agent: the shell was signalled while the agent beneath it kept running and held the output pipe, so the sweep waited indefinitely. The process tree is now taken down and the call returns even if something survives.
  - A killed agent loses its buffered output, which shows where a timed-out attempt got stuck. It is now asked to stop and forced only afterwards.
  - Launching the sweep from inside another coding agent passed the launcher's session and settings file through the environment, pinning a model that beat the command line, so the row recorded the local model while another answered. The host-session variables are cleared and the run writes its own model into settings beside the work directory.
- Agent events no longer appear twice in the execution view (stored history plus a live replay buffer). Events are deduplicated by identity where published, and a failed history read leaves the live stream running.
- A tool-template error from Ollama is no longer retried; it is deterministic, so retries only delayed the fallback to the text channel.
- The context accounting reported by a run is reset per turn instead of carrying the previous turn's dropped-message counters.
- The SSE stream of an MCP server was opened with a call that waits for the whole body, so no event ever arrived. It is now read as it arrives.
- A tool-template error from Ollama is recognised in more wordings: a failure like `parse ... call ... malformed ... parameter` was treated as an ordinary 500, retried six times and ended the turn despite the text-channel fallback. Words are matched whole, so an unrelated 500 mentioning a "parser" is still retried.
- A streamed call was costed from the local price table even when the provider reported its charge, so a model billed at an unknown rate (a router with its own prices) showed the wrong cost. The reported figure is now preferred.
- An agent turn prompt lost the boundary marking its cacheable system prefix on the way to the adapter, so every iteration re-billed instructions plus tool schemas at the full input rate.
- Gemini through OpenRouter failed on every turn that had used a tool, because the conversation ended on a model turn, which Google rejects. The last message is now handed over as a user turn.
- The deterministic judge read a correctly drawn artifact as blank because it sampled only the first 200x200 pixels of a canvas, and a game board under its start menu counted as empty. The whole canvas is sampled on a grid, and a blank canvas counts as empty only with little surrounding text.
- Regenerating a benchmark task from a changed catalog case kept its `hidden` flag, so a hidden task reappeared in results after any edit.
- The judge could see which model it scored, because its evidence directory was named after the result id. It now has a neutral name.
- A broken game loop throwing the same error every frame no longer fills the judge's evidence with identical console lines; back-to-back repeats are kept once.
- Saving `results.json` on Windows failed when the dev server or a browser had the file open. The save now retries briefly while locked and fails at once on other errors.

## [0.0.2.0] - 2026-09-07

### Added

- Closing a project window now releases everything Refio held for it (HTTP clients, caches, background scopes, MCP child processes), which previously lived until the IDE exited. A `ProjectManagerListener` (`ProjectCloseListener`) calls the new `CoreConnectionManager.closeProject`.
- Stop now aborts the call that is in flight. `ExecutionMonitor` had a `streamingJob` field that was never assigned, so `cancelStreaming()` cancelled nothing and a turn in a non-streamed LLM call ran until it returned. The turn's coroutine is now registered in `SessionManager.sendMessage`.
- `EmbeddingProvider` is `AutoCloseable` so its HTTP engine can be released; its default `close()` is empty, and `OpenAICompatibleEmbeddingProvider` closes only a client it built itself.
- `view_diff` refuses a file larger than the configured `FileLimits.maxFileSize`; it read both sides fully into memory.

### Changed

- Cancelling a turn is reported as a stop, not a failure, so Stop no longer shows an error toast. `TurnExecutor` used to swallow the `CancellationException` and return a failed result; it now persists what the user saw and rethrows. Same fix in `TurnToolExecutor` (a cancelled tool no longer marks its subtask FAILED), `InvokeSubagentTool`, `OllamaAdapter` and `CoreSessionService`.
- The completion verifier can no longer fail a finished turn: a missing user message, a fenced answer or any call exception now leaves the turn standing with a log warning. A fenced answer is unwrapped through `JsonExtractor`.
- The project analysis cache is no longer invalidated by the agent's own edits.
- The turn's history-size label is counted in SQL (`countByTaskId`) instead of loading the whole conversation to take its `.size` each iteration.
- The Marketplace plugin description was rewritten to match the product and the known-limitations list corrected (the multi-agent runtime is CLI-only; testing is claimed for IntelliJ IDEA only). Same pass over `README.md` and `docs/ROADMAP.md`.

### Fixed

- HTTP engines are no longer leaked on every model-list refresh, RAG query and docs search: `ModelRegistry` refreshes via `listModelsAndClose`, `RagRouter` and `EmbeddingsService` reuse or close their engines (one cached per provider id), and the codebase and docs context providers close theirs.
- A router replaced at startup is closed: `CoreConnectionManager` used to overwrite the cached entry, leaving the old router's clients and scopes alive.
- Every API log line opened a SQLite transaction to check that `api_logs` exists; the answer is now remembered process-wide.
- `DualLogger.trace` and `debug` no longer build and redact messages nobody receives (once per streamed token for trace).
- `SecureLogger.redactAndTruncate` redacted the whole payload for a 60-character preview. It now narrows the input first, with a 512-character margin so a secret straddling the cut is still matched.
- `FileLockManager` could hand two callers a lock on the same file after an eviction. The caller now re-checks the map after acquiring and starts over if needed.
- A cancelled or failed turn no longer leaves subagent stream collectors attached, which re-rendered the previous run's subagent bubbles in the next turn; cleanup now runs in an uncancellable `finally`.

## [0.0.1.16] - 2026-08-19

### Added

- Per-provider concurrent-request ceiling (`providers.max_concurrent`, default 4), so a parallel-subagent fan-out no longer trips cloud rate limits (OpenRouter 429s). Ollama keeps its own per-endpoint gate, and the 429 retry still catches leftover bursts.
- `read_file` tool bubbles gained an expandable "Read content" panel showing the full text as it entered the conversation (line-number prefixes, `[Lines X-Y of Z]` markers), carried on a transient `ToolCallResult.fullOutput` and never persisted.
- Vertical navigation rail (`RefioRail` + the `RefioScreen` enum) replaces the horizontal tab strip: one icon per screen, `Alt+1..8` kept, an accent dot on a background screen with new content. Leaving advanced mode while an advanced-only screen (Context, Agents, RAG, Debug, Logs, API) is open falls back to Chat.
- "Now running" bar above the transcript (`NowRunningBar`): current step, tool, elapsed time and Stop. The spinner stops while waiting for permission or the user. Rules live in a unit-tested `NowRunningState`.
- Plan summary header above the step list (`PlanSummaryPanel`): steps passed or failed and where the time went, as a verdict line plus progress bar.
- Embeddings from any OpenAI-compatible server (`OpenAICompatibleEmbeddingProvider`): `models.embedding_model: openai_compatible/<model>`, `providers.embeddings.embeddings_base_url` (+ optional `embeddings_api_key`). The built-in providers pin `api.openai.com` or Ollama's `/api/embed`. Vector width is learned from the last response, and vectors are reordered by `index` only when it forms a complete permutation.
- `providers.generic_openai.generic_openai_context_size` (default 32768) declares the context window of an OpenAI-compatible server, since `/v1/models` on llama.cpp and friends reports no `context_length`. It has no validator, so a very large window can be declared.
- `providers.generic_openai.generic_openai_raw_request` sends the chat request without our sampling parameters (`temperature`, `max_tokens`, `request_id`) for servers that pin generation settings. `stream` / `stream_options` and `tools` / `tool_choice` are kept (token accounting and AGENT mode need them). `generic_openai` only.
- Settings -> Providers (and the TUI settings) gained a Context Size dropdown per local provider, the raw-request toggle and a Model field for the OpenAI-compatible provider. A `config.yaml` value outside the offered set shows the nearest lower size (`nearestNumericOption`).
- Responsive layout: the panel picks a width band (narrow / normal / wide) and in a narrow dock shrinks the rail, collapses the status bar and shows icon-only composer buttons, removing the horizontal scrollbar.
- Soft guard for "defines agents but never runs one": after two consecutive `manage_subagent` create/update calls with no `invoke_subagent`, the turn injects a short note naming the agents already defined (a local model once spent four turns re-creating one agent). It fires at most once per turn.
- Per-turn budgets `agent.max_turn_tokens` (default 1200000) and `agent.max_turn_minutes` (default 45) stop a turn that keeps spending without converging, which `agent.max_cost_usd` cannot do for a free local model. They are checked each iteration; 0 disables either.
- Every tool call runs under a timeout (2 min in PLAN, 5 min in AGENT, 3 min for network tools) enforced with a real thread interrupt. The setting existed but nothing used it, so a `grep_search` pattern over one very long line could block its thread and ignore cancellation.
- Agent file changes can be rolled back from the per-edit snapshots: `planRestore` reports what a restore would do, `restoreSnapshot` performs it. Limits: file creation cannot be undone, a restore is not atomic across files, takes no file lock, and does not refresh the RAG index or working memory.
- `rename_symbol` refuses a rename whose usage list spans more than 20 files unless `confirm_wide_rename` is set, counting before writing anything. The text engine used to rewrite every file containing the word, with a snapshot of the anchor file only.
- The approval prompt shows the command about to run (up to 300 characters), so `cat README.md` and `cat ~/.ssh/id_rsa` can be told apart.

### Changed

- Tool calls in the transcript are single 24 px rows (`ToolCallRow` + `ToolCallRowView`) instead of cards, expanding on click into an IDE diff (when a pre-write snapshot exists) or the raw output. Detail is built on first expansion and survives a re-render.
- The execution screen shows one 26 px row per step (`StepRowView` + `StepListRenderer`) instead of ~95 px cards; a selected failed step explains itself in a lower splitter pane.
- Session history is a flat two-line list (`SessionRow` + `SessionListRenderer`), about a dozen sessions per screen instead of three. Row actions moved to the toolbar and context menu, and the filters to one popup button.
- The status bar shows only what fits a 22 px strip (engine state, context fill, requests, tokens, cost), with details in a tooltip, repaints at most four times a second, and formats via a tested `StatusBarFormat`.
- Settings panels (General, Advanced, Context, Providers, Models, Tools, Docs, MCP, Prompts, Subagents, Theme) were rebuilt on the Kotlin UI DSL; values still save on change. The category column is an icon-only strip with tooltips.
- Every settings page shrinks to a docked tool window. `SettingsLayout.kt` provides `settingsForm { }` (trims the DSL's left indent), `settingsScrollPane()` (adopts the viewport width), and `fitColumns` / `showFullValueOnHover` (small column minimums plus a full-value tooltip). Tables lost their fixed preferred width.
- `ModelWindow` gained a step between the provider override and the global fallback: a window discovered from the provider's model listing (cached, no extra I/O), so unknown models no longer fall to the global default. `general.max_context_size` also acts as a ceiling whenever the user set it.
- `ChatService` no longer caps the window by `general.max_context_size` a second time, which shrank a declared provider window back to the default when the key was unset.
- The session list is stacked while docked and single-line when wide, and the title budget follows the row width instead of a flat 42 characters.
- The status bar shows context fill in tokens next to the percentage (`47K/128K  38%`), truncated to whole thousands (32768 shows as `32K`); omitted until a window is resolved.
- The settings titled border uses a tighter horizontal than vertical inset (`LCATheme.createSettingsBorder`).
- The GPT-5.1 and GPT-5.2 Codex variants were removed from the OpenAI direct-API whitelist (`/v1/responses` answers 404 `model_not_found` for our key type). They stay available through OpenRouter (`openai/gpt-5.1-codex...`), and Codex 5.3+ still works directly.
- The streaming assistant bubble shows a live char counter (`Generating... · N chars`), so a slow producer (e.g. Ollama native-tools) no longer looks hung.
- Next-speaker-judge re-entry is capped at one nudge once a text deliverable of 100+ chars exists, removing the duplicate regeneration and second "Agent guidance" message on text-only turns. Early turns keep the budget of 2.
- Subagents always have `think`, `tasks` and `memory`, even under a `tools:` whitelist that omits them, fixing personas whose body calls `think(...)` and loops on "Tool 'think' is not available to the subagent". Egress tools (`web_search`, `http_request`, `fetch_webpage`) and the shell still need an explicit entry. `SubagentToolFilter.isToolAllowedUnderProfile` now backs the prompt tool matrix and call validation.
- A read-only subagent that produced a substantial prose report is accepted as delivered instead of being re-entered by the completion judge. A short intent stub still gets re-entry, and write-capable subagents are unchanged.
- A top-level AGENT turn whose deliverable is prose (analysis, review, report) is accepted as done once it has a substantial answer (600+ chars, no file write), instead of being finalized INCOMPLETE with a spurious "Refio prompted the agent to finish the remaining steps" note.
- The completion judge reads the whole reply (head+tail only above 4096 chars) instead of the first 2000 chars.
- `manage_subagent(action="create")` is idempotent: the same definition is a no-op, a different one applies an update and says so, instead of failing with "already exists, use action='update'". Builtin names stay protected.
- `manage_subagent`, `memory` and `tasks` name their valid actions when `action` is missing, instead of "action required".
- Directory listings (`read_directory`, `file_search`) are compressed structurally (entries grouped per directory with counts, total size and sample names) instead of summarized by the weak model, which once turned a 29 KB listing into 562 characters with no directory name. The same grouping applies in recent work; the full listing stays retrievable via `memory(action="get_subtask_output")`.
- The chat transcript is single-column at every width; the wide-panel side timeline duplicated the transcript and the "now running" bar and was removed with its saved splitter proportion.
- The turn iteration cap moved from 100 to 200 and its warning from 80 to 50; the per-turn token and time budgets now bound the work too.
- A project's `.refio/config.yaml` finally takes effect. Seeded application-scope database rows outranked YAML, so the nine keys with a YAML accessor (`general.no_egress_enabled`, `general.execution_mode`, `general.reasoning_effort`, `ui.selected_mode`, `models.embedding_model`, `general.format_markdown`, `general.streaming_enabled`, `general.advanced_view`, `providers.zai.zai_base_url`) were never read from the project file. Project YAML is now materialized into the project scope.
- `tools.permissions` in `config.yaml` is read back, so "Reload from YAML" no longer ignores per-tool permissions.
- An MCP server in CONTEXT mode no longer calls every tool it exposes on every turn with the raw prompt, write tools and CHAT included (these calls bypassed permissions and approval). `contextToolName` ("use only this tool") is now honored.
- The next-speaker judge no longer treats a short reply (a 30-character test) as proof that the user speaks next.
- The intent-announcement heuristic ("I will now do X") was removed from the completion judge; deliverable-aware finalization covers it.

### Fixed
- Opening or refreshing the Context panel no longer compacts the conversation: the preview ran `ConversationSummaryService`, spending a weak-model call and writing a summary row. Compaction is opt-out per call (`allowSummarization`) and the preview opts out.
- A failed compaction no longer costs the turn its project context. A weak-summarizer outage (provider down, no-egress, rate limit) used to drop the entire project context from the prompt; the turn now continues on the uncompacted history.
- A failed multi-agent run now records why: the router copied only the turn's `success` flag, so an agent that came back FAILED without an error string left the run.json error list empty.
- `run_terminal_command` reports the exit code of a failed command instead of its raw output (`grep -c` with no match exits 1 printing `0`, which reached the agent as a bare `Error: 0`).

- A locally served model was rejected before the request left the client ("context too large") however the window was configured: `TokenEstimator` had a second resolver ending in a hardcoded 32768. `TokenEstimator.getMaxContextForModel` now wraps `ModelWindow.resolve`, and without a `ConfigService` the pre-flight check is skipped with a warning.
- `general.no_egress_enabled` blocked a self-hosted embedding endpoint, breaking fully local RAG. Embedding calls now use `NetworkPolicy.assertRemoteEgressAllowed` (no-egress means "do not leave my network"); tools that reach the public internet keep the strict gate. One shared `NetworkPolicy.isLocalTarget` (localhost, `0.0.0.0`, `192.168.*`, `10.*`; an unparsable URL counts as remote) is reused by the LLM path.
- A turn that wrote its file and then repeated its sign-off was reported as failed, because the text-repetition guard ended it unconditionally. The abort is now deliverable-aware: with a file on disk the turn finalizes as done; with nothing written it is still stopped.
- A finished turn was reported as failed, discarding the answer the user had seen: after judge re-entry a model returning an empty envelope hit the empty-content give-up, which only recognised a file write. An answer the guardian is holding now counts as a deliverable and is persisted once.
- The "now running" bar stayed up after the agent stopped, since the turn-state flow was reset on only 4 of about 20 loop exits. The reset now happens in `TurnPersistence.finish`, for the top-level turn only.
- Settings category icons jumped sideways on hover because a cell was one pixel wider than the viewport, so `JBList` re-painted it in an offset popup. Cells now take their width from the list and the expandable-item handler is off for the strip.
- A settings page in a narrow dock silently lost its right-hand side (`HORIZONTAL_SCROLLBAR_NEVER` over a preferred-width layout inside a `Scrollable` view). The page now follows the viewport width down to a 320 px floor, then falls back to a horizontal scrollbar. Covered by `SettingsLayoutTest`.
- Settings tables clipped trailing columns at dock width (Tools Plan/Agent selectors, API-log status). Minimums are smaller and hovering an elided cell shows its full value.
- The Docs settings actions overflowed a docked window; the destructive action moved to its own row.
- The `[SLOW_TOOL]` warning no longer fires on `invoke_subagent` / `delegate_to_strong_model`, whose multi-minute runs are expected.
- After `advance_code_editing` finished, the "Generated content" panel showed the `edit_description` parameters instead of the code. `reconcileMessages` now keeps the completed edit transient over its blank persisted twin (in memory only; after a reload the bubble shows the diff summary).
- The ACE "Show content" collapsible did not paint until the window was resized; the cached panel is now rebuilt when content goes blank -> non-blank.
- The `edit_description` parameter was shown under a "Generated content" label; a params block is now titled by its key and the metadata line is labelled per source.
- `delegate_to_strong_model` with `allow_tools=true` always aborted with a false "Subagent recursion detected for 'strong-model'" because the tool added its own name to the child's ancestor chain. Genuine strong-model -> strong-model recursion is still caught.
- Read-only analysis subagents were killed by the byte-identical-output loop guard (threshold 2) when re-reading a file to double-check. Read-only tools (`read_file`, `grep_search`, `file_search`, `rag_search`, `read_directory`, `code_intelligence`) now use a threshold of 4; write/exec tools keep the tight one.
- The tool-error-rate guard could abort a turn that had just recovered. It now aborts only when the most recent tool call also failed.
- A turn that kept calling tools it does not have (profile-blocked) could loop until cancelled. A backstop now aborts it (INCOMPLETE) after two consecutive blocked calls and points the model to its available tools.
- A streamed assistant report vanished on Stop or the iteration limit, replaced by "Operation cancelled by user." Those exits now persist the last substantial streamed prose.
- The context-overflow guard warned `[CTX] overflow` on prompts the provider accepted, because its per-model table fell back to 128k for unlisted models. It now uses `ModelWindow.resolve` and the dead table was removed.
- Provider ids are lowercased where a `provider/model` string is first parsed, so "Openrouter/..." and "openrouter/..." no longer split metrics, api-log filters and logs. Model ids keep their casing.
- The Agents Graph showed finished sessions and subagents as `[RUNNING]` forever. Each run now emits a final `TurnEnded` at every exit and the graph flips the node to COMPLETED or FAILED.
- Parallel subagents crashed on a UNIQUE-constraint violation (`subtasks.task_id, order_index`) from a read-max-then-insert race. `SubtaskRepository.createNext` allocates and inserts in one retry-guarded step.
- A subagent turn that threw an uncaught exception left its Agents-Graph node `[RUNNING]`. A backstop now emits `TurnEnded(success=false)` and rethrows the original error.
- A subagent saw every other agent's plan in `<agent_plans>`, which could make a weak model call `tasks(update)` on a step it never created ("Step N not found"). A subagent now sees only its own plan.
- After judge re-entry, an empty model reply burned two full iterations on format nudges. It now ends the turn at once with the answer the judge holds; a recoverable envelope in the model's thinking still wins.
- Subtask kinds now cover every registered tool (`manage_subagent`, `think`, `tasks`, `memory`, `code_intelligence`, `rag_search`, `find_usages`, `rename_symbol`, the process and messaging tools, `llm_call`, `delegate_to_strong_model`, `ask_user`, `sleep`). They were stored as the generic `PLAN_STEP`, which made the subtask list unreadable and made working memory skip them after a restart.
- `advance_code_editing` silently truncated a file at the first nested code fence (a triple backtick in column 0) and still reported "File edited successfully". Markdown, prompt files and docs were the real carriers.
- A mid-stream error from OpenRouter was swallowed and the truncated answer returned as a success: the stream handler rethrew only `IllegalStateException`, so the mapped provider error skipped the retry added for 429s. Before any content the turn failed with a misleading "prompt exceeded the model's context window".
- `multi_edit` replaced the first occurrence of a string without checking uniqueness, while `code_editing` refuses with "String appears N times in file". The schema has no `replace_all` or `occurrence` parameter.
- BLOCK command rules could be stepped over with a prefix (`env rm -rf X`), because patterns were compiled without multiline, so `^` anchored only at position 0. That downgraded a hard prohibition to an ordinary prompt.
- Session trust rules now end with the session; the reset existed but had no production caller.
- `general.no_egress_enabled` could be defeated by a hostname: `http://10.attacker.example.com:11434` passed the local-target test and received the full code context, and a cloned repository could supply it through `.refio/config.yaml`. The same test also blocked legitimate LAN names. One shared classifier now serves every egress gate.
- The streaming path did not enforce a tool set to OFF (only the mode check), while `advance_code_editing` and `multi_line_editor` take it whenever a listener is attached (plugin and TUI).
- A failed MCP connection leaked its process or HTTP clients for the application's lifetime, since the connection entered the map only after a successful `connect()`. Handshake timeouts, non-MCP servers, 401/404 and wrong URLs landed there, with status DISCONNECTED rather than ERROR, so the probe waited out its timeout.
- `MCPManager.shutdown()` did nothing in the CLI: it was called without the project id it was initialized under.
- Recreating a project's router dropped all its MCP tools while still reporting a non-zero tool count (opening Settings or the RAG view before the chat window was enough).
- Two concurrent `connectServer` calls for the same server could start two processes and orphan one.
- Concurrent `send()` on the stdio transport corrupted JSON-RPC framing, leaving both callers waiting out a 30 s timeout.
- `advance_code_editing` and `multi_line_editor` rewrote every line ending from CRLF to LF, invisibly to the diff and the no-op check (a tool could report "No changes applied" after rewriting the file).
- A large edit could crash the process with an out-of-memory error after the file was written: the change summary used a full LCS matrix (1.6 GB for a 20000-line file; the error is not an `Exception`, so nothing caught it), and the agent never learned the edit succeeded. In the plugin this endangered the IDE.
- A failure to connect to the LLM was never retried: the connection error type missed the retryable-message test, and a connect timeout against a still-loading model was mapped to it before the timeout branch.
- The embeddings circuit breaker counted every HTTP failure twice, opening after three errors instead of the configured five (duplicated in five methods).
- A RAG reindex could leave a file with a fresh checksum and no chunks permanently (separate transactions; classification compares only the checksum; only Clear Index recovered). The mirror case and the file-analysis and documentation indexers had the same pattern and are fixed.
- RAG search materialized every chunk above the score threshold, with content, although a heap had already reduced the set to the top K.
- A repeated-text abort discarded the deliverable in PLAN and in subagents (the produced-something test received an empty string), so the plan's steps were never created and a subagent returned a loop message instead of its report.
- `working_memory.max_facts` had no effect (the limit was always the hardcoded 20), and per-task entries were released only when the IDE closed.
- A queued approval dialog could expire before it was shown, since the timeout started at queueing.
- Disconnecting an MCP server left pending requests hanging for 30 s with a misleading "request timed out", and cancelling a turn left the request registered.
- Self-daemonizing background processes survived application shutdown, because the hook checks only the launched shell. The process manager now tracks descendants, as the terminal tool already did.
- Session cost totals were $0.00 for cheap models, because the accumulator truncated to whole cents instead of rounding.
- The build verifier could hand the model a truncated build-error list (unsynchronized buffer read before the reader finished).
- A subagent killed by a guardrail stayed marked as running in the agents graph forever; the terminal event is now emitted at the shared finalization point.
- Incremental tool-call streaming from Ollama dropped every call but the last.
- The chars-per-token prior treated an unrecognized model name as a loose cloud tokenizer, under-counting dense local models by about 9%, which is the only way to detect that Ollama silently truncated a prompt (`ornith:35b` is a qwen-family mixture-of-experts; `gpt-oss` matched "gpt"). Cloud tokenizers are now named explicitly and everything else is assumed dense.
- The chars-per-token calibrator learned from truncated requests, inflating its ratio and blinding the overflow guard. Samples from a possibly cut request are discarded.
- A refusal from an approval policy ended the whole turn as if the user had asked to stop; in a 258-run campaign 10 headless runs died this way, 8 after writing their deliverable. A policy denial is now returned to the model as a failed tool result naming the blocked call; a human rejection still ends the turn.
- The system-prompt token estimate ignored the model id and ran about 9% low for dense local models.

### Removed

- The per-turn token ceiling (`agent.max_turn_tokens`): large turns are normal for a free local model, so it stopped progressing turns more often than runaway ones. `agent.max_turn_minutes` stays and its default drops from 45 to 30 minutes. A leftover `agent.max_turn_tokens` entry in `config.yaml` is ignored.
- `agent.max_iterations`. It was seeded, validated and printed by `--print-config` but never read (the real cap is 200, set per mode in `TurnLoopConfig.plan()/agent()`), so `--config agent.max_iterations=80` did nothing. A leftover `config.yaml` entry is ignored.
- Eleven `TurnLoopConfig` fields that no code read (`enableVerification = false` sat next to `verify.enabled = true`): `enableAutoCompaction`, `compactionThreshold`, `summarizationThreshold`, `warningThreshold`, `maxFormatRetries`, `enableWorkingMemory`, `workingMemoryMaxEntries`, `enableVerification`, `verificationIterationThreshold`, `enablePromptCaching`, `cacheablePrefix`. Each is owned elsewhere (`ConversationSummaryService`, `tool_summary.*`, `working_memory.max_facts`, `verify.*`, the Anthropic adapter's `cache_control` markers, the prompt provider). No behavior change.

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
- Cached-token cost accounting: input tokens split into fresh / cache-read / cache-write, each billed at its own rate, so a cache-served prompt is no longer charged as fresh. Cache-read prices: OpenAI, Anthropic (0.1x input) and Gemini (0.25x input); models without one use the full input rate.
- A `cached_tokens` session counter (new DB column) with a `💾` indicator in the TUI status line and the IntelliJ StatusBar.
- `general.reasoning_effort` (OFF/LOW/MEDIUM/HIGH) replaces the boolean `general.thinking_enabled`; each level maps per adapter to the provider-native knob (OpenAI/OpenRouter reasoning effort, Anthropic/Gemini thinking-token budget, Ollama on/off). Selectors added to the TUI settings and the plugin General settings.
- Subagent history isolation: every subagent turn gets an agent-instance id even when the caller assigned none, so its steps no longer leak into the parent thread or inflate its token budget.
- Multi-agent debug export (`SessionDebugExporter.exportMultiAgent`) writes a per-agent snapshot (ordered by start time, aggregate status and token totals).
- Two orchestration e2e scenarios (`subagent-locate-and-fix`, `subagent-two-file-fix`) checking the parent stays on-task after a read-heavy delegation.
- `TransientErrorClassifier` - one definition of "retryable upstream failure" (bare HTTP 500 with an empty body, Cloudflare 520, Anthropic 529 overloaded), used by `LLMRetryHandler` and by `advance_code_editing`.
- `advance_code_editing` salvages a truncated generation: when the stream ends without a closing fence but the body is 2 KB+, the near-complete file is written and flagged. A truncated block also short-circuits the extraction-repair loop.
- `advance_code_editing` retries a transient editor-LLM error with bounded backoff (2 retries, 1 s / 2 s), only while nothing has streamed to the UI yet.
- Repeated-full-regeneration nudge: rebuilding one path whole-file 3x in a turn (`advance_code_editing` / `create_new_file`) injects a soft SYSTEM hint to make a targeted edit or deliver. Targeted-edit tools never count.
- The native-vs-JSON tool-routing decision (`ProjectContextResponse.nativeToolsDecision`) is shown in the plugin Debug panel.
- `TaskStatus.CANCELED` is recorded when the user presses Stop, instead of `FAILED`.

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
- Chat render pipeline rewritten (IntelliJ): the message `StateFlow` is collected directly and throttled with a trailing delay (one rebuild per 300 ms) instead of a `MutableSharedFlow(extraBufferCapacity = 1)` + `sample`, so a burst of streaming deltas can no longer swallow the last update. The field is `uiUpdateThrottleMs`; it must not become a debounce, which never fires while a tool generates.
- ChatView collectors moved to `Dispatchers.IO`: on `Dispatchers.Default` a long tool stream starved the UI collectors.
- `kotlinx-coroutines-core` excluded from the plugin's runtime classpath (the IntelliJ Platform provides it). A second bundled copy caused a `ServiceLoader` clash that silently stalled `StateFlow` collectors. Compilation and the CLI are unaffected.
- A tool bubble is marked live (`isStreaming` / `isToolStreaming`) at creation instead of at its first delta, and `MessageDispatcher.reconcileMessages` treats an `EXECUTING` tool call as live.
- Tool-call bubbles are deduplicated by `toolCallId` on reload: while the call streams the in-memory bubble wins, and once it finishes the persisted row takes over. Keyed by `toolCallId`, never `agentName`, so earlier turns of a repeatedly-invoked subagent stay visible.
- Streaming deltas whose accumulated text has not grown are skipped instead of rebuilding the whole message list.
- Stop cancels the running turn `Job`, so cancellation reaches the in-flight LLM HTTP call instead of only flipping UI state.
- `advance_code_editing` requests the model's full output budget; adapters clamp to the per-model limit. An explicit caller `maxTokens` is honored up to the model limit on all adapters (Anthropic, Gemini, Ollama, OpenAI-compatible); `limits.max_output_size` is the default when none is passed and the ceiling when the model's limit is unknown.
- `RepetitionDetector` threshold raised 4 -> 32 identical adjacent blocks, since legitimate structured output (table cells, list items) has a small bounded run while a decoder loop is unbounded. Large-block runaway stays covered by the 128 KB size limiter and the 180 s wall-clock deadline.
- The model chosen in the dropdown is persisted to APP-scope `ui.selected_model` and applied to a new session, so the CODING slot (`advance_code_editing`) no longer generates with a stale model.
- `system-agent.md`: the "do not validate static content" rule now says writing a static file finishes it - no re-reading, no parsing its inline scripts, no self-authored grep-for-feature checkers, the most common way a turn spirals.
- CLI/TUI numeric formatting uses `Locale.US`.
- Hot-path regexes precompiled (`JsonExtractor`, `TokenEstimator`, `ToolResultCompression`, `ToolCallParser`); JSON extraction is single-pass balanced-brace.

### Fixed

- Chat bubbles appeared only after resizing the tool window or dragging the splitter: the lossy `tryEmit` render hop dropped the last update of a burst, and `messagesPanel.revalidate()` stopped at the enclosing `JViewport`. The whole scroll chain is revalidated now.
- The live char counter in `advance_code_editing` never moved: the tool bubble was created non-streaming, so a mid-turn reload dropped it and later deltas produced an equal list the `StateFlow` never emitted (3433 deltas over 62 s, zero UI updates).
- One tool call rendered as two bubbles after a mid-turn reload (the streaming transient and its persisted row carry different ids).
- Stop hung on OpenAI-compatible streaming (Z.AI/GLM, LM Studio, OpenRouter, generic OpenAI): the SSE loop never got the cancel check, so the stream was read to completion first.
- A bare upstream `HTTP 500` / `520` / `529` killed a whole session instead of being retried.
- Weak models on the JSON-in-text path that emit tool params as siblings of `tool` (flat action shape, seen on gemma4:31b) failed with "Missing required parameter"; the leftover keys are now mapped as arguments.
- A turn that already wrote its file then narrated "let me verify..." burned extra iterations: the guardian no longer drops native tool-calling after a landed write, and the dangling intent stub is replaced with a factual completion note.
- Orphaned background-process hang: `run_terminal_command` force-kills the whole descendant tree and drains stdout on its own thread, so a backgrounded child (`python app.py &`) no longer hangs the turn (`orphan_reaped` in metadata).
- `ProcessManager` kills the whole descendant tree on stop, reaps children via a JVM shutdown hook, and serves continuously drained output.
- Code-intelligence/refactor child processes drain output on a daemon thread and force-destroy on timeout instead of deadlocking.
- Concurrent LLM completions no longer lose metric updates - atomic in-DB increments; task stats via one grouped SQL `SUM`.
- `MultiAgentRunner` runs its completion path even when agent setup fails, so dependents don't wait forever.
- Verification still red after all repair rounds ends the turn as a real failure, not fake success.
- Cost overcharge on cached context: a cache-read hit was billed at the full input rate. Anthropic cache-read was 10x over (now 0.1x), and a real gpt-5.4-nano run now matches the actual OpenAI bill instead of ~5x under.
- Corrected OpenAI list prices used for cost estimates (`gpt-5.4-nano`, `gpt-5.4-mini`, `gpt-5.5`).
- OpenRouter model resolution matches the most specific prefix over an ordered map, so `openai/gpt-5.1*` keeps its 400k context window and own pricing instead of the 128k `gpt-` family.

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
