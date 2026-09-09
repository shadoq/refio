// @vitest-environment node
import { describe, it, expect } from "vitest";
import { filterInboxEntries, inboxFacetOptions } from "@/lib/queueFilters";

const E = [
  {
    taskId: "snake",
    modelId: "m1",
    environmentId: "local",
    harnessId: "refio",
    autoVerdict: { verdict: "PASS" },
  },
  {
    taskId: "snake",
    modelId: "m2",
    environmentId: "cloud",
    harnessId: "claude-code",
    autoVerdict: { verdict: "FAIL" },
  },
  { taskId: "todo", modelId: "m1", environmentId: "local", harnessId: "refio" }, // no verdict
];

describe("filterInboxEntries", () => {
  it("returns all entries when no facet is set", () => {
    expect(filterInboxEntries(E, {})).toHaveLength(3);
  });

  it("filters by task, model and environment independently", () => {
    expect(filterInboxEntries(E, { taskId: "snake" })).toHaveLength(2);
    expect(filterInboxEntries(E, { modelId: "m1" }).map((e) => e.taskId)).toEqual(["snake", "todo"]);
    expect(filterInboxEntries(E, { environmentId: "cloud" })).toHaveLength(1);
  });

  it("filters by verdict and excludes entries that have no verdict", () => {
    // A verdict filter is a hard requirement: the no-verdict todo entry drops out.
    expect(filterInboxEntries(E, { verdict: "PASS" })).toHaveLength(1);
    expect(filterInboxEntries(E, { verdict: "FAIL" })).toHaveLength(1);
  });

  // Reviewing the reference track is a different job from reviewing Refio runs, so
  // the queue has to be able to show one without the other.
  it("filters by harness", () => {
    expect(filterInboxEntries(E, { harnessId: "refio" })).toHaveLength(2);
    expect(filterInboxEntries(E, { harnessId: "claude-code" }).map((e) => e.modelId)).toEqual([
      "m2",
    ]);
  });

  it("combines active facets with AND", () => {
    expect(filterInboxEntries(E, { taskId: "snake", modelId: "m2" })).toHaveLength(1);
    expect(filterInboxEntries(E, { taskId: "snake", verdict: "PASS" })).toHaveLength(1);
    expect(filterInboxEntries(E, { taskId: "todo", verdict: "PASS" })).toHaveLength(0);
  });
});

describe("inboxFacetOptions", () => {
  it("returns the distinct, sorted facet values present in the queue", () => {
    const o = inboxFacetOptions(E);
    expect(o.taskIds).toEqual(["snake", "todo"]);
    expect(o.modelIds).toEqual(["m1", "m2"]);
    expect(o.environmentIds).toEqual(["cloud", "local"]);
    expect(o.harnessIds).toEqual(["claude-code", "refio"]);
  });
});
