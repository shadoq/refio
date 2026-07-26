import { TasksFileSchema, type TasksFile } from "@/schema/tasks";
import { ResultsFileSchema, type ResultsFile, type Score } from "@/schema/results";

async function save(fileKey: "tasks" | "results", data: unknown): Promise<void> {
  if (!import.meta.env.DEV) {
    throw new Error("Saving is only available in dev mode");
  }
  const res = await fetch("/__save", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ file: fileKey, data }),
  });
  if (!res.ok) {
    throw new Error(`Save failed: ${res.status} ${await res.text()}`);
  }
}

export async function saveTasks(file: TasksFile): Promise<void> {
  TasksFileSchema.parse(file);
  await save("tasks", file);
}

export async function saveResults(file: ResultsFile): Promise<void> {
  ResultsFileSchema.parse(file);
  await save("results", file);
}

// Apply a single inbox promote/discard on the server against the latest results.json,
// instead of overwriting the whole file from the client's (possibly stale) cache. The
// server returns the updated file so the caller can refresh its cache.
export async function mutateResults(
  op: "promote" | "discard",
  entryId: string,
  scores?: Score[],
): Promise<ResultsFile> {
  if (!import.meta.env.DEV) {
    throw new Error("Saving is only available in dev mode");
  }
  const res = await fetch("/__mutate-results", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ op, entryId, scores }),
  });
  if (!res.ok) {
    throw new Error(`Mutate failed: ${res.status} ${await res.text()}`);
  }
  const json = (await res.json()) as { ok: boolean; data: ResultsFile };
  return json.data;
}

export async function uploadAttachment(
  resultId: string,
  file: File,
  metadata?: {
    modelProvider?: string;
    modelName?: string;
    modelId?: string;
    attemptNumber?: number;
    fileNumber?: number;
  },
): Promise<string> {
  if (!import.meta.env.DEV) {
    throw new Error("Uploads are only available in dev mode");
  }
  const formData = new FormData();
  formData.append("resultId", resultId);
  if (metadata?.modelProvider) formData.append("modelProvider", metadata.modelProvider);
  if (metadata?.modelName) formData.append("modelName", metadata.modelName);
  if (metadata?.modelId) formData.append("modelId", metadata.modelId);
  if (metadata?.attemptNumber) {
    formData.append("attemptNumber", String(metadata.attemptNumber));
  }
  if (metadata?.fileNumber) formData.append("fileNumber", String(metadata.fileNumber));
  formData.append("file", file, file.name);

  const res = await fetch("/__upload", {
    method: "POST",
    body: formData,
  });
  if (!res.ok) {
    throw new Error(`Upload failed: ${res.status} ${await res.text()}`);
  }
  const json = (await res.json()) as { ok: boolean; path: string };
  return json.path;
}
