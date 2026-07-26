// @vitest-environment node
import { describe, it, expect } from "vitest";
import { filterInboxEntries, inboxFacetOptions } from "@/lib/queueFilters";

const E = [
  { taskId: "snake", modelId: "m1", environmentId: "local", autoVerdict: { verdict: "PASS" } },
  { taskId: "snake", modelId: "m2", environmentId: "cloud", autoVerdict: { verdict: "FAIL" } },
  { taskId: "todo", modelId: "m1", environmentId: "local" }, // no verdict
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
  });
});
