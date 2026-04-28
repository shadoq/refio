import { useMutation, useQueryClient } from "@tanstack/react-query";
import { saveResults, saveTasks } from "./saver";
import { RESULTS_KEY, TASKS_KEY } from "./queries";
import type { Result, ResultsFile, Model, Environment } from "@/schema/results";
import type { Task, TasksFile } from "@/schema/tasks";

// ─── Results ────────────────────────────────────────────────────────────────

export function useUpsertResult() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { current: ResultsFile; result: Result }) => {
      const idx = input.current.results.findIndex((r) => r.id === input.result.id);
      const next = [...input.current.results];
      if (idx >= 0) next[idx] = input.result;
      else next.push(input.result);
      const file: ResultsFile = { ...input.current, results: next };
      await saveResults(file);
      return file;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: RESULTS_KEY }),
  });
}

export function useDeleteResult() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { current: ResultsFile; resultId: string }) => {
      const file: ResultsFile = {
        ...input.current,
        results: input.current.results.filter((r) => r.id !== input.resultId),
      };
      await saveResults(file);
      return file;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: RESULTS_KEY }),
  });
}

// ─── Models ─────────────────────────────────────────────────────────────────

export function useUpsertModel() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { current: ResultsFile; model: Model }) => {
      const idx = input.current.models.findIndex((m) => m.id === input.model.id);
      const next = [...input.current.models];
      if (idx >= 0) next[idx] = input.model;
      else next.push(input.model);
      const file: ResultsFile = { ...input.current, models: next };
      await saveResults(file);
      return file;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: RESULTS_KEY }),
  });
}

export function useDeleteModel() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { current: ResultsFile; modelId: string }) => {
      const file: ResultsFile = {
        ...input.current,
        models: input.current.models.filter((m) => m.id !== input.modelId),
      };
      await saveResults(file);
      return file;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: RESULTS_KEY }),
  });
}

// ─── Environments ────────────────────────────────────────────────────────────

export function useUpsertEnvironment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { current: ResultsFile; environment: Environment }) => {
      const idx = input.current.environments.findIndex(
        (e) => e.id === input.environment.id,
      );
      const next = [...input.current.environments];
      if (idx >= 0) next[idx] = input.environment;
      else next.push(input.environment);
      const file: ResultsFile = { ...input.current, environments: next };
      await saveResults(file);
      return file;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: RESULTS_KEY }),
  });
}

export function useDeleteEnvironment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { current: ResultsFile; environmentId: string }) => {
      const file: ResultsFile = {
        ...input.current,
        environments: input.current.environments.filter(
          (e) => e.id !== input.environmentId,
        ),
      };
      await saveResults(file);
      return file;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: RESULTS_KEY }),
  });
}

// ─── Tasks ───────────────────────────────────────────────────────────────────

export function useUpsertTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { current: TasksFile; task: Task }) => {
      const idx = input.current.tasks.findIndex((t) => t.id === input.task.id);
      const next = [...input.current.tasks];
      if (idx >= 0) next[idx] = input.task;
      else next.push(input.task);
      const file: TasksFile = { ...input.current, tasks: next };
      await saveTasks(file);
      return file;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: TASKS_KEY }),
  });
}

export function useDeleteTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { current: TasksFile; taskId: string }) => {
      const file: TasksFile = {
        ...input.current,
        tasks: input.current.tasks.filter((t) => t.id !== input.taskId),
      };
      await saveTasks(file);
      return file;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: TASKS_KEY }),
  });
}

export function useUpdateCoreCriteria() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { current: TasksFile; coreCriteria: TasksFile["coreCriteria"] }) => {
      const file: TasksFile = { ...input.current, coreCriteria: input.coreCriteria };
      await saveTasks(file);
      return file;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: TASKS_KEY }),
  });
}
