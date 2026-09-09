// Pure, in-memory mutations of the results file for the import path: ensure a
// model/environment reference exists and upsert an inbox entry. No IO and no node
// imports, so it type-checks under the src project and is testable by vitest; the
// tsx importer combines these with the judge store's atomic save.
import type { InboxEntry } from "../../schema/results";

export interface ResultsFileLike {
  models?: Array<{ id: string; name: string; provider: string }>;
  environments?: Array<{ id: string; name: string; type: "local" | "cloud" }>;
  harnesses?: Array<{ id: string; name: string; kind: "refio" | "external" }>;
  inbox?: InboxEntry[];
  [key: string]: unknown;
}

export function ensureModel(file: ResultsFileLike, modelId: string): void {
  const models = (file.models ??= []);
  if (!models.some((m) => m.id === modelId)) {
    const provider = modelId.includes("/") ? modelId.split("/")[0] : "unknown";
    models.push({ id: modelId, name: modelId, provider });
  }
}

export function ensureEnvironment(file: ResultsFileLike, envId: string): void {
  const envs = (file.environments ??= []);
  if (!envs.some((e) => e.id === envId)) {
    envs.push({ id: envId, name: envId, type: "local" });
  }
}

// Only the id "refio" is our own CLI; every other harness is an external coding agent.
// The stand-in record keeps the views labelled until someone fills in the version and
// the run conditions by hand.
export function ensureHarness(file: ResultsFileLike, harnessId: string): void {
  const harnesses = (file.harnesses ??= []);
  if (!harnesses.some((h) => h.id === harnessId)) {
    harnesses.push({
      id: harnessId,
      name: harnessId,
      kind: harnessId === "refio" ? "refio" : "external",
    });
  }
}

export function upsertInbox(file: ResultsFileLike, entry: InboxEntry): void {
  const inbox = (file.inbox ??= []);
  const idx = inbox.findIndex((e) => e.id === entry.id);
  if (idx >= 0) inbox[idx] = entry;
  else inbox.push(entry);
}
