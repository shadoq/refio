import type { Result } from "@/schema/results";
import { formatDuration } from "@/lib/format";

export interface AdminFact {
  label: string;
  value: string;
  /** When true, the UI renders the value with a copy affordance. */
  copyable?: boolean;
}

export interface AdminFactsContext {
  taskName?: string;
  modelName?: string;
  environmentName?: string;
  environmentType?: string;
}

// Administrative facts about a single result: the raw identifiers, timestamps
// and exact metrics that the public result view hides. Names are resolved with
// a fallback to the raw id so an orphaned result (whose task/model/env was
// deleted) still reads clearly instead of rendering blanks.
export function resultAdminFacts(result: Result, ctx: AdminFactsContext = {}): AdminFact[] {
  return [
    { label: "Result ID", value: result.id, copyable: true },
    { label: "Task", value: withRawId(ctx.taskName, result.taskId) },
    { label: "Model", value: withRawId(ctx.modelName, result.modelId) },
    { label: "Environment", value: environmentValue(result.environmentId, ctx) },
    { label: "Attempt", value: `#${result.attemptNumber}` },
    { label: "Run at", value: result.runAt },
    { label: "Created at", value: result.createdAt },
    { label: "Duration", value: durationValue(result.durationMs) },
    { label: "Tokens in", value: numberValue(result.tokensIn) },
    { label: "Tokens out", value: numberValue(result.tokensOut) },
    { label: "Cost", value: result.costUsd == null ? "-" : `$${result.costUsd}` },
    { label: "Attachments", value: String(result.attachments.length) },
  ];
}

function withRawId(name: string | undefined, rawId: string): string {
  return name ? `${name} (${rawId})` : rawId;
}

function environmentValue(envId: string, ctx: AdminFactsContext): string {
  if (!ctx.environmentName) return envId;
  const typeSuffix = ctx.environmentType ? `, ${ctx.environmentType}` : "";
  return `${ctx.environmentName} (${envId}${typeSuffix})`;
}

function durationValue(ms: number | undefined): string {
  if (ms == null) return "-";
  return `${formatDuration(ms)} (${ms} ms)`;
}

function numberValue(value: number | undefined): string {
  return value == null ? "-" : String(value);
}
