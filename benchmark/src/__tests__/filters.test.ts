// @vitest-environment node
import { describe, it, expect, beforeEach } from "vitest";
import { useFilters, applyFilters } from "@/store/filters";

const row = (id: string, harnessId: string) => ({
  id,
  modelId: "anthropic/claude-opus-5",
  environmentId: "anthropic-cloud",
  taskId: "snake",
  harnessId,
  runAt: "2026-09-01T09:00:00.000Z",
});

const refioRow = row("r1", "refio");
const externalRow = row("r2", "claude-code");

describe("harness filtering", () => {
  beforeEach(() => {
    useFilters.getState().clear();
  });

  // The reference track measures a different system (an external agent plus its own
  // model), so it must never land in the main table without being asked for.
  it("hides an external-harness result under the default filter", () => {
    const kept = applyFilters([refioRow, externalRow], useFilters.getState());
    expect(kept.map((r) => r.id)).toEqual(["r1"]);
  });

  it("shows the external track once its harness is selected", () => {
    useFilters.getState().setHarnessIds(["refio", "claude-code"]);
    const kept = applyFilters([refioRow, externalRow], useFilters.getState());
    expect(kept.map((r) => r.id)).toEqual(["r1", "r2"]);
  });

  it("shows only the external track when it alone is selected", () => {
    useFilters.getState().setHarnessIds(["claude-code"]);
    const kept = applyFilters([refioRow, externalRow], useFilters.getState());
    expect(kept.map((r) => r.id)).toEqual(["r2"]);
  });

  // "Clear filters" must not be a back door that leaks the reference track into the
  // main table: clearing returns to refio-only, not to everything.
  it("returns to the refio-only default after clearing", () => {
    useFilters.getState().setHarnessIds(["refio", "claude-code"]);
    useFilters.getState().clear();
    const kept = applyFilters([refioRow, externalRow], useFilters.getState());
    expect(kept.map((r) => r.id)).toEqual(["r1"]);
  });

  it("leaves the other filter dimensions untouched", () => {
    useFilters.getState().setTaskIds(["todo-app"]);
    const kept = applyFilters([refioRow], useFilters.getState());
    expect(kept).toEqual([]);
  });
});
