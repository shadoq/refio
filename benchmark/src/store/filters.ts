import { create } from "zustand";

// The main table answers "which model for Refio", so it shows the Refio track only.
// The reference track (external coding agents) is opt-in, and clearing filters must
// return here rather than open the table to everything.
export const DEFAULT_HARNESS_IDS = ["refio"];

interface FiltersState {
  modelIds: string[];
  environmentIds: string[];
  taskIds: string[];
  harnessIds: string[];
  dateRange: [string, string] | null;
  setModelIds: (ids: string[]) => void;
  setEnvironmentIds: (ids: string[]) => void;
  setTaskIds: (ids: string[]) => void;
  setHarnessIds: (ids: string[]) => void;
  setDateRange: (range: [string, string] | null) => void;
  clear: () => void;
}

export const useFilters = create<FiltersState>((set) => ({
  modelIds: [],
  environmentIds: [],
  taskIds: [],
  harnessIds: DEFAULT_HARNESS_IDS,
  dateRange: null,
  setModelIds: (ids) => set({ modelIds: ids }),
  setEnvironmentIds: (ids) => set({ environmentIds: ids }),
  setTaskIds: (ids) => set({ taskIds: ids }),
  setHarnessIds: (ids) => set({ harnessIds: ids }),
  setDateRange: (range) => set({ dateRange: range }),
  clear: () =>
    set({
      modelIds: [],
      environmentIds: [],
      taskIds: [],
      harnessIds: DEFAULT_HARNESS_IDS,
      dateRange: null,
    }),
}));

export function applyFilters<
  T extends {
    modelId: string;
    environmentId: string;
    taskId: string;
    harnessId: string;
    runAt: string;
  },
>(
  results: T[],
  f: Pick<
    FiltersState,
    "modelIds" | "environmentIds" | "taskIds" | "harnessIds" | "dateRange"
  >,
): T[] {
  return results.filter((r) => {
    if (f.modelIds.length && !f.modelIds.includes(r.modelId)) return false;
    if (f.environmentIds.length && !f.environmentIds.includes(r.environmentId)) return false;
    if (f.taskIds.length && !f.taskIds.includes(r.taskId)) return false;
    if (f.harnessIds.length && !f.harnessIds.includes(r.harnessId)) return false;
    if (f.dateRange && (r.runAt < f.dateRange[0] || r.runAt > f.dateRange[1])) return false;
    return true;
  });
}
