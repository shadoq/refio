// @vitest-environment node
import { describe, it, expect } from "vitest";
import { limitsForTier, DEFAULT_AGENT_LIMITS } from "@/lib/catalog/agent-limits";

describe("limitsForTier", () => {
  // A cap that bites measures the cap, not the agent; a stress task needs hours.
  it("gives a stress task two hours", () => {
    expect(limitsForTier("stress").timeoutMs).toBe(7_200_000);
    expect(limitsForTier("stress").maxTurns).toBe(200);
  });

  it("keeps an easy task short", () => {
    expect(limitsForTier("easy").timeoutMs).toBe(900_000);
  });

  it("falls back to the default for a task with no tier", () => {
    expect(limitsForTier(undefined)).toEqual(DEFAULT_AGENT_LIMITS);
    expect(limitsForTier("bogus")).toEqual(DEFAULT_AGENT_LIMITS);
  });
});
