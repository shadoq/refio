import { create } from "zustand";

interface FiltersState {
  modelIds: string[];
  environmentIds: string[];
  taskIds: string[];
  dateRange: [string, string] | null;
  setModelIds: (ids: string[]) => void;
  setEnvironmentIds: (ids: string[]) => void;
  setTaskIds: (ids: string[]) => void;
  setDateRange: (range: [string, string] | null) => void;
  clear: () => void;
}

export const useFilters = create<FiltersState>((set) => ({
  modelIds: [],
  environmentIds: [],
  taskIds: [],
  dateRange: null,
  setModelIds: (ids) => set({ modelIds: ids }),
  setEnvironmentIds: (ids) => set({ environmentIds: ids }),
  setTaskIds: (ids) => set({ taskIds: ids }),
  setDateRange: (range) => set({ dateRange: range }),
  clear: () => set({ modelIds: [], environmentIds: [], taskIds: [], dateRange: null }),
}));

export function applyFilters<T extends { modelId: string; environmentId: string; taskId: string; runAt: string }>(
  results: T[],
  f: Pick<FiltersState, "modelIds" | "environmentIds" | "taskIds" | "dateRange">,
): T[] {
  return results.filter((r) => {
    if (f.modelIds.length && !f.modelIds.includes(r.modelId)) return false;
    if (f.environmentIds.length && !f.environmentIds.includes(r.environmentId)) return false;
    if (f.taskIds.length && !f.taskIds.includes(r.taskId)) return false;
    if (f.dateRange && (r.runAt < f.dateRange[0] || r.runAt > f.dateRange[1])) return false;
    return true;
  });
}
