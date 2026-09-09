import { Select, Button, Space } from "antd";
import { useSearchParams } from "react-router-dom";
import { useEffect } from "react";
import { useFilters, DEFAULT_HARNESS_IDS } from "@/store/filters";
import { useTasks, useResults } from "@/data/queries";

function parseIds(param: string | null): string[] {
  if (!param) return [];
  return param.split(",").filter(Boolean);
}

function serializeIds(ids: string[]): string {
  return ids.join(",");
}

export function GlobalFilters() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useFilters();
  const { data: tasksData } = useTasks();
  const { data: resultsData } = useResults();

  // Sync URL → Zustand on mount and when URL changes
  useEffect(() => {
    filters.setModelIds(parseIds(searchParams.get("models")));
    filters.setEnvironmentIds(parseIds(searchParams.get("envs")));
    filters.setTaskIds(parseIds(searchParams.get("tasks")));
    // No harness in the URL means the default track, not "all tracks": the reference
    // runs stay out of the main view until someone asks for them.
    const harnessParam = parseIds(searchParams.get("harnesses"));
    filters.setHarnessIds(harnessParam.length > 0 ? harnessParam : DEFAULT_HARNESS_IDS);
    filters.setDateRange(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  function updateParam(key: string, value: string) {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value) next.set(key, value);
        else next.delete(key);
        return next;
      },
      { replace: true },
    );
  }

  function handleModels(ids: string[]) {
    updateParam("models", serializeIds(ids));
  }

  function handleEnvs(ids: string[]) {
    updateParam("envs", serializeIds(ids));
  }

  function handleTasks(ids: string[]) {
    updateParam("tasks", serializeIds(ids));
  }

  function handleHarnesses(ids: string[]) {
    // Clearing the picker means "back to the default track", never "show everything".
    updateParam("harnesses", serializeIds(ids.length > 0 ? ids : DEFAULT_HARNESS_IDS));
  }

  function handleClear() {
    setSearchParams({}, { replace: true });
  }

  const modelOptions = (resultsData?.models ?? []).map((m) => ({
    label: m.name,
    value: m.id,
  }));

  const envOptions = (resultsData?.environments ?? []).map((e) => ({
    label: e.name,
    value: e.id,
  }));

  const taskOptions = (tasksData?.tasks ?? []).map((t) => ({
    label: t.name,
    value: t.id,
  }));

  const harnesses = resultsData?.harnesses ?? [];
  const harnessOptions = harnesses.map((h) => ({ label: h.name, value: h.id }));

  const hasFilters =
    filters.modelIds.length > 0 ||
    filters.environmentIds.length > 0 ||
    filters.taskIds.length > 0 ||
    filters.harnessIds.join(",") !== DEFAULT_HARNESS_IDS.join(",");

  return (
    <Space size="small" wrap className="filters">
      <Select
        mode="multiple"
        allowClear
        placeholder="Models"
        options={modelOptions}
        value={filters.modelIds}
        onChange={handleModels}
        style={{ minWidth: 140 }}
        maxTagCount="responsive"
      />
      <Select
        mode="multiple"
        allowClear
        placeholder="Environments"
        options={envOptions}
        value={filters.environmentIds}
        onChange={handleEnvs}
        style={{ minWidth: 140 }}
        maxTagCount="responsive"
      />
      <Select
        mode="multiple"
        allowClear
        placeholder="Tasks"
        options={taskOptions}
        value={filters.taskIds}
        onChange={handleTasks}
        style={{ minWidth: 120 }}
        maxTagCount="responsive"
      />
      {harnesses.length > 1 && (
        <Select
          mode="multiple"
          placeholder="Harness"
          options={harnessOptions}
          value={filters.harnessIds}
          onChange={handleHarnesses}
          style={{ minWidth: 140 }}
          maxTagCount="responsive"
        />
      )}
      {hasFilters && (
        <Button size="small" onClick={handleClear}>
          Clear
        </Button>
      )}
    </Space>
  );
}
