import { create } from "zustand";
import type { RefioMode } from "@/lib/stats";

const STORAGE_KEY = "benchmark-refio-mode";

function initialMode(): RefioMode {
  try {
    return localStorage.getItem(STORAGE_KEY) === "relative" ? "relative" : "absolute";
  } catch {
    return "absolute";
  }
}

interface RefioModeState {
  mode: RefioMode;
  setMode: (mode: RefioMode) => void;
}

// One choice for the whole viewer: every page that shows the Refio Score reads it.
export const useRefioMode = create<RefioModeState>((set) => ({
  mode: initialMode(),
  setMode: (mode) => {
    try {
      localStorage.setItem(STORAGE_KEY, mode);
    } catch {
      // Storage blocked: the choice still holds for this visit.
    }
    set({ mode });
  },
}));
