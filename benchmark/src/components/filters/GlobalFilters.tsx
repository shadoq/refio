import { Select, Button, Space } from "antd";
import { useSearchParams } from "react-router-dom";
import { useEffect } from "react";
import { useFilters } from "@/store/filters";
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

  const hasFilters =
    filters.modelIds.length > 0 ||
    filters.environmentIds.length > 0 ||
    filters.taskIds.length > 0;

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
      {hasFilters && (
        <Button size="small" onClick={handleClear}>
          Clear
        </Button>
      )}
    </Space>
  );
}
