// Pure filtering helpers for the admin review queue. Structurally typed so they stay
// unit-testable and decoupled from the full InboxEntry schema.

export interface QueueFilters {
  taskId?: string;
  modelId?: string;
  environmentId?: string;
  verdict?: "PASS" | "FAIL";
}

type FilterableEntry = {
  taskId: string;
  modelId: string;
  environmentId: string;
  autoVerdict?: { verdict: string };
};

// Keep only the entries matching every active facet. An unset facet matches everything;
// an active `verdict` facet is a hard requirement, so entries without a verdict drop out.
export function filterInboxEntries<T extends FilterableEntry>(
  entries: T[],
  filters: QueueFilters,
): T[] {
  return entries.filter((e) => {
    if (filters.taskId && e.taskId !== filters.taskId) return false;
    if (filters.modelId && e.modelId !== filters.modelId) return false;
    if (filters.environmentId && e.environmentId !== filters.environmentId) return false;
    if (filters.verdict && e.autoVerdict?.verdict !== filters.verdict) return false;
    return true;
  });
}

// Distinct, sorted facet values present in the queue, for populating the filter dropdowns.
export function inboxFacetOptions<T extends FilterableEntry>(
  entries: T[],
): { taskIds: string[]; modelIds: string[]; environmentIds: string[] } {
  const taskIds = new Set<string>();
  const modelIds = new Set<string>();
  const environmentIds = new Set<string>();
  for (const e of entries) {
    taskIds.add(e.taskId);
    modelIds.add(e.modelId);
    environmentIds.add(e.environmentId);
  }
  const sorted = (s: Set<string>) => [...s].sort();
  return {
    taskIds: sorted(taskIds),
    modelIds: sorted(modelIds),
    environmentIds: sorted(environmentIds),
  };
}
