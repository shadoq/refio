import { useQuery } from "@tanstack/react-query";
import { fetchTasks, fetchResults } from "./loaders";

export const TASKS_KEY = ["tasks"] as const;
export const RESULTS_KEY = ["results"] as const;

export function useTasks() {
  return useQuery({
    queryKey: TASKS_KEY,
    queryFn: fetchTasks,
    staleTime: 60_000,
  });
}

export function useResults() {
  return useQuery({
    queryKey: RESULTS_KEY,
    queryFn: fetchResults,
    staleTime: 60_000,
  });
}
