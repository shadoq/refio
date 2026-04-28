// @vitest-environment node
import { describe, it, expect } from "vitest";
import { paretoFront } from "@/lib/paretoFront";

describe("paretoFront", () => {
  it("returns single point as Pareto front", () => {
    const front = paretoFront([{ id: "a", x: 1, y: 0.8 }]);
    expect(front.has("a")).toBe(true);
  });

  it("selects point with lower cost AND higher score", () => {
    // a: cost=1, score=0.9 — dominates b
    // b: cost=2, score=0.7 — dominated by a
    const front = paretoFront([
      { id: "a", x: 1, y: 0.9 },
      { id: "b", x: 2, y: 0.7 },
    ]);
    expect(front.has("a")).toBe(true);
    expect(front.has("b")).toBe(false);
  });

  it("keeps both points when neither dominates the other", () => {
    // a: lower cost, lower score; b: higher cost, higher score
    const front = paretoFront([
      { id: "a", x: 1, y: 0.5 },
      { id: "b", x: 3, y: 0.9 },
    ]);
    expect(front.has("a")).toBe(true);
    expect(front.has("b")).toBe(true);
  });

  it("handles three points with one dominated", () => {
    // a(1, 0.6): cheap, decent   — Pareto (lower cost than b, b has higher score)
    // b(2, 0.9): pricey, great   — Pareto (higher score than a, a has lower cost)
    // c(3, 0.5): expensive, bad  — dominated by a (a.x=1<3 AND a.y=0.6>0.5)
    const front = paretoFront([
      { id: "a", x: 1, y: 0.6 },
      { id: "b", x: 2, y: 0.9 },
      { id: "c", x: 3, y: 0.5 },
    ]);
    expect(front.has("c")).toBe(false);
    expect(front.has("a")).toBe(true);
    expect(front.has("b")).toBe(true);
  });

  it("returns empty set for empty input", () => {
    expect(paretoFront([])).toEqual(new Set());
  });

  it("keeps equal-score equal-cost points (neither dominates)", () => {
    const front = paretoFront([
      { id: "a", x: 1, y: 0.8 },
      { id: "b", x: 1, y: 0.8 },
    ]);
    // Neither strictly dominates the other — both on front
    expect(front.has("a")).toBe(true);
    expect(front.has("b")).toBe(true);
  });
});
