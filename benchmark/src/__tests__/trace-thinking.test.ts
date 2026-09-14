// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  claudeThinkingEvidence,
  refioThinkingEvidence,
  buildThinking,
} from "@/lib/trace/thinking";
import type { TimedLine } from "@/lib/trace/types";

const line = (o: unknown, tMs = 0): TimedLine => ({ line: JSON.stringify(o), tMs });

describe("claudeThinkingEvidence", () => {
  // Claude Code reports a running estimate of the reasoning tokens as they stream, and
  // an assistant message carries a thinking block. Either is proof the model reasoned.
  it("sees reasoning reported as a running token estimate", () => {
    const evidence = claudeThinkingEvidence([
      line({ type: "system", subtype: "init" }),
      line({ type: "system", subtype: "thinking_tokens", estimated_tokens: 12 }),
      line({ type: "system", subtype: "thinking_tokens", estimated_tokens: 480 }),
    ]);
    expect(evidence).toEqual({ observed: true, tokens: 480 });
  });

  it("sees a thinking block in an assistant message", () => {
    const evidence = claudeThinkingEvidence([
      line({
        type: "assistant",
        message: { content: [{ type: "thinking", thinking: "weighing options" }] },
      }),
    ]);
    expect(evidence.observed).toBe(true);
  });

  it("reports no reasoning when the run never showed any", () => {
    const evidence = claudeThinkingEvidence([
      line({ type: "assistant", message: { content: [{ type: "text", text: "done" }] } }),
    ]);
    expect(evidence).toEqual({ observed: false, tokens: null });
  });
});

describe("refioThinkingEvidence", () => {
  it("sees reasoning recorded on a conversation message", () => {
    const evidence = refioThinkingEvidence({
      conversation: [{ role: "ASSISTANT", reasoningContent: "let me plan" }],
    });
    expect(evidence.observed).toBe(true);
  });

  it("reports none for a run document that carries no reasoning", () => {
    expect(refioThinkingEvidence({ conversation: [{ role: "ASSISTANT" }] }).observed).toBe(false);
  });
});

describe("buildThinking", () => {
  it("keeps what was asked for next to what actually happened", () => {
    expect(buildThinking("off", { observed: true, tokens: 100 })).toEqual({
      requested: "off",
      observed: true,
      tokens: 100,
    });
  });

  it("records the level when the harness set one", () => {
    expect(buildThinking("on", { observed: false, tokens: null }, "high")).toEqual({
      requested: "on",
      observed: false,
      level: "high",
    });
  });
});
