// Did the model reason before answering, and was it allowed to? A thinking model with
// reasoning off behaves like a different model, so the setting belongs in the data next
// to the score. What the harness ASKED for is recorded by the runner; what actually
// happened is read here out of the run's own event stream, because a model can ignore
// the setting and a harness cannot always express it.
import type { Thinking } from "../../schema/results";
import type { TimedLine } from "./types";

export interface ThinkingEvidence {
  observed: boolean;
  // Reasoning tokens the run reported; null when it reports none.
  tokens: number | null;
}

const NO_EVIDENCE: ThinkingEvidence = { observed: false, tokens: null };

export function claudeThinkingEvidence(lines: TimedLine[]): ThinkingEvidence {
  let observed = false;
  let tokens: number | null = null;

  for (const { line } of lines) {
    const trimmed = line.trim();
    if (trimmed === "") continue;
    let event: Record<string, unknown>;
    try {
      event = JSON.parse(trimmed) as Record<string, unknown>;
    } catch {
      continue;
    }
    if (event.type === "system" && event.subtype === "thinking_tokens") {
      observed = true;
      // The estimate is cumulative, so the last one is the total for the run.
      const estimate = event.estimated_tokens;
      if (typeof estimate === "number" && Number.isFinite(estimate)) tokens = estimate;
    } else if (event.type === "assistant") {
      const message = (event.message ?? {}) as Record<string, unknown>;
      const content = Array.isArray(message.content) ? message.content : [];
      if (content.some((b) => (b as Record<string, unknown>)?.type === "thinking")) observed = true;
    }
  }
  return { observed, tokens };
}

// Refio records a message's reasoning next to its content when the model produced any.
export function refioThinkingEvidence(runJson: unknown): ThinkingEvidence {
  const doc = (runJson ?? {}) as Record<string, unknown>;
  const conversation = Array.isArray(doc.conversation) ? doc.conversation : [];
  for (const raw of conversation) {
    const msg = (raw ?? {}) as Record<string, unknown>;
    const reasoning = msg.reasoningContent ?? msg.reasoning ?? msg.thinking;
    if (typeof reasoning === "string" && reasoning.trim() !== "") {
      return { observed: true, tokens: null };
    }
  }
  return NO_EVIDENCE;
}

export function buildThinking(
  requested: Thinking["requested"],
  evidence: ThinkingEvidence,
  level?: string,
): Thinking {
  const thinking: Thinking = { requested, observed: evidence.observed };
  if (level !== undefined) thinking.level = level;
  if (evidence.tokens !== null) thinking.tokens = evidence.tokens;
  return thinking;
}
