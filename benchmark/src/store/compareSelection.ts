import { create } from "zustand";

const MAX_COMPARE = 4;

interface CompareSelectionState {
  modelIds: string[];
  toggleModel: (id: string) => void;
  setModels: (ids: string[]) => void;
  clear: () => void;
}

export const useCompareSelection = create<CompareSelectionState>((set) => ({
  modelIds: [],
  toggleModel: (id) =>
    set((state) => {
      if (state.modelIds.includes(id)) {
        return { modelIds: state.modelIds.filter((m) => m !== id) };
      }
      if (state.modelIds.length >= MAX_COMPARE) return state;
      return { modelIds: [...state.modelIds, id] };
    }),
  setModels: (ids) => set({ modelIds: ids.slice(0, MAX_COMPARE) }),
  clear: () => set({ modelIds: [] }),
}));
