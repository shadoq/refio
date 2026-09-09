// @vitest-environment node
import { describe, it, expect } from "vitest";
import { parseClaudeCodeRun, parseCodexRun, toRunJson } from "@/lib/catalog/agent-run";
import { parseRunJson } from "@/lib/catalog/inbox";

// The shape `claude -p --output-format json` prints: one result event carrying the
// final text, wall-clock duration, usage and billed cost.
const claudeResultEvent = {
  type: "result",
  subtype: "success",
  is_error: false,
  duration_ms: 128_000,
  num_turns: 7,
  result: "Wrote snake_claude-code_01.html",
  total_cost_usd: 0.42,
  usage: {
    input_tokens: 1200,
    output_tokens: 8400,
    cache_creation_input_tokens: 300,
    cache_read_input_tokens: 15_000,
  },
};

describe("parseClaudeCodeRun", () => {
  it("reads status, duration, cost and final text from the result event", () => {
    const run = parseClaudeCodeRun(JSON.stringify(claudeResultEvent), 0);
    expect(run.status).toBe("SUCCESS");
    expect(run.durationMs).toBe(128_000);
    expect(run.costUsd).toBe(0.42);
    expect(run.finalOutput).toBe("Wrote snake_claude-code_01.html");
    expect(run.tokensOut).toBe(8400);
  });

  // Cached and cache-creation tokens are prompt tokens the model still had to be
  // given. Counting only `input_tokens` would report a fraction of the real prompt
  // and make the cost-per-token comparison with a local model meaningless.
  it("counts cached prompt tokens as input tokens", () => {
    const run = parseClaudeCodeRun(JSON.stringify(claudeResultEvent), 0);
    expect(run.tokensIn).toBe(1200 + 300 + 15_000);
  });

  it("reads the result event out of an event array", () => {
    const stdout = JSON.stringify([
      { type: "system", subtype: "init", model: "claude-opus-5" },
      claudeResultEvent,
    ]);
    const run = parseClaudeCodeRun(stdout, 0);
    expect(run.status).toBe("SUCCESS");
    expect(run.costUsd).toBe(0.42);
  });

  it("reports FAILED when the run errored", () => {
    const stdout = JSON.stringify({ ...claudeResultEvent, is_error: true, subtype: "error" });
    expect(parseClaudeCodeRun(stdout, 0).status).toBe("FAILED");
  });

  it("reports FAILED on a non-zero exit even when stdout is unreadable", () => {
    const run = parseClaudeCodeRun("not json at all", 1);
    expect(run.status).toBe("FAILED");
    expect(run.tokensIn).toBeUndefined();
  });
});

// `codex exec --json` prints one JSON object per line. The event names are not a
// stable contract, so the parser scans for usage numbers wherever they appear rather
// than binding to one envelope.
const codexJsonl = [
  JSON.stringify({ type: "thread.started", thread_id: "t1" }),
  JSON.stringify({ type: "item.completed", item: { type: "agent_message", text: "working" } }),
  JSON.stringify({
    type: "token_count",
    info: {
      total_token_usage: { input_tokens: 5400, output_tokens: 2100, cached_input_tokens: 900 },
    },
  }),
  JSON.stringify({ type: "turn.completed" }),
].join("\n");

describe("parseCodexRun", () => {
  it("extracts the total token usage wherever the event carries it", () => {
    const run = parseCodexRun(codexJsonl, "Done.", 0, 96_000);
    expect(run.tokensIn).toBe(5400);
    expect(run.tokensOut).toBe(2100);
    expect(run.status).toBe("SUCCESS");
    expect(run.finalOutput).toBe("Done.");
    expect(run.durationMs).toBe(96_000);
  });

  it("takes the last usage report when several are printed", () => {
    const stream = [
      JSON.stringify({ type: "token_count", info: { total_token_usage: { input_tokens: 10, output_tokens: 5 } } }),
      JSON.stringify({ type: "token_count", info: { total_token_usage: { input_tokens: 900, output_tokens: 400 } } }),
    ].join("\n");
    const run = parseCodexRun(stream, "", 0, 1000);
    expect(run.tokensIn).toBe(900);
    expect(run.tokensOut).toBe(400);
  });

  // Codex bills through a subscription, so there is no per-run cost to report. An
  // absent field is honest; a zero would read as "this run was free".
  it("leaves cost unset rather than reporting zero", () => {
    const run = parseCodexRun(codexJsonl, "Done.", 0, 1000);
    expect(run.costUsd).toBeUndefined();
  });

  it("leaves token counts unset when no usage event was printed", () => {
    const run = parseCodexRun(JSON.stringify({ type: "turn.completed" }), "Done.", 0, 1000);
    expect(run.tokensIn).toBeUndefined();
    expect(run.tokensOut).toBeUndefined();
  });

  it("ignores an unparseable line instead of failing the whole run", () => {
    const run = parseCodexRun(`garbage\n${codexJsonl}`, "Done.", 0, 1000);
    expect(run.tokensIn).toBe(5400);
  });

  it("reports FAILED on a non-zero exit", () => {
    expect(parseCodexRun(codexJsonl, "", 2, 1000).status).toBe("FAILED");
  });
});

// The import path reads every run through parseRunJson, so an external agent's run
// is only usable if it survives that round trip unchanged.
describe("toRunJson", () => {
  it("produces a document parseRunJson reads back with the same metrics", () => {
    const agentRun = parseClaudeCodeRun(JSON.stringify(claudeResultEvent), 0);
    const parsed = parseRunJson(toRunJson(agentRun));
    expect(parsed.status).toBe("SUCCESS");
    expect(parsed.finalOutput).toBe("Wrote snake_claude-code_01.html");
    expect(parsed.metrics.durationMs).toBe(128_000);
    expect(parsed.metrics.tokensIn).toBe(16_500);
    expect(parsed.metrics.tokensOut).toBe(8400);
    expect(parsed.metrics.costUsd).toBe(0.42);
  });

  it("omits a metric the agent did not report instead of writing zero", () => {
    const agentRun = parseCodexRun(JSON.stringify({ type: "turn.completed" }), "ok", 0, 5000);
    const parsed = parseRunJson(toRunJson(agentRun));
    expect(parsed.metrics.tokensIn).toBeUndefined();
    expect(parsed.metrics.costUsd).toBeUndefined();
    expect(parsed.metrics.durationMs).toBe(5000);
  });

  // An external agent runs its own tool loop and does not report Refio tool names.
  // An empty list is the truthful answer; the deterministic judge must not be fed
  // invented tool calls.
  it("reports no tool calls for an external agent", () => {
    const parsed = parseRunJson(toRunJson(parseCodexRun("", "ok", 0, 1)));
    expect(parsed.toolCalls).toEqual([]);
  });
});
