// Refio's headless run.json carries the whole conversation, so the trace is read
// straight from it: every assistant message is a turn, every tool call detail is a
// call, every tool message is a result. Defensive throughout - a truncated or partial
// document must degrade to fewer events, never throw.
import { classifyTool, summarizeArgs } from "./tool-classes";
import type { TraceEvent } from "./types";
import { shorten } from "./types";

const HARNESS = "refio";

function num(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

// Tool arguments arrive as a JSON string; an argument list we cannot parse is still
// worth showing, just uninterpreted.
function argsOf(raw: unknown): string {
  if (typeof raw !== "string") return summarizeArgs(raw);
  try {
    return summarizeArgs(JSON.parse(raw));
  } catch {
    return shorten(raw);
  }
}

export function normalizeRefioRunJson(runJson: unknown): TraceEvent[] {
  const doc = (runJson ?? {}) as Record<string, unknown>;
  const session = (doc.session ?? {}) as Record<string, unknown>;
  const conversation = Array.isArray(doc.conversation) ? doc.conversation : [];

  const events: TraceEvent[] = [];
  let turn = 0;
  let i = 0;
  // Whether each call succeeded, as the run document itself reports it, queued in call order and
  // consumed by the tool results that answer them - the same FIFO pairing the summary uses.
  //
  // This used to be guessed by matching the word "error" at the start of the result preview, which
  // is not the same question and does not mean the same thing as it does for the other harnesses:
  // a tool whose output happens to begin with that word was counted as a failure, and a failure
  // whose message did not was missed. Older documents carry no flags, so the guess stays as the
  // fallback for them.
  const reportedOk: (boolean | null)[] = [];
  const startedAt = num((conversation[0] as Record<string, unknown> | undefined)?.createdAt);
  const at = (msg: Record<string, unknown>): number | null => {
    const created = num(msg.createdAt);
    return created === null || startedAt === null ? null : created - startedAt;
  };

  for (const raw of conversation) {
    const msg = (raw ?? {}) as Record<string, unknown>;
    const role = String(msg.role ?? "").toLowerCase();
    const tMs = at(msg);
    const preview = typeof msg.contentPreview === "string" ? msg.contentPreview : "";

    if (role === "assistant") {
      turn += 1;
      events.push({
        i: i++, tMs, turn, kind: "assistant_text", tool: null, cls: null, args: null, ok: null,
        text: shorten(preview),
      });
      const details = Array.isArray(msg.toolCallDetails) ? msg.toolCallDetails : [];
      for (const rawDetail of details) {
        const detail = (rawDetail ?? {}) as Record<string, unknown>;
        const tool = String(detail.name ?? "unknown");
        reportedOk.push(typeof detail.ok === "boolean" ? detail.ok : null);
        events.push({
          i: i++, tMs, turn, kind: "tool_call", tool, cls: classifyTool(HARNESS, tool),
          args: argsOf(detail.arguments), ok: null, text: null,
        });
      }
    } else if (role === "tool") {
      const reported = reportedOk.shift();
      events.push({
        i: i++, tMs, turn, kind: "tool_result", tool: null, cls: null, args: null,
        ok: reported ?? !/^error/i.test(preview.trim()), text: shorten(preview),
      });
    }
  }

  const lastAt = conversation.length > 0
    ? at((conversation[conversation.length - 1] ?? {}) as Record<string, unknown>)
    : null;
  events.push({
    i, tMs: lastAt, turn, kind: "run_end", tool: null, cls: null, args: null, ok: null,
    text: typeof session.status === "string" ? session.status : "UNKNOWN",
  });
  return events;
}
