// @vitest-environment node
import { describe, it, expect } from "vitest";
import { extractJson } from "@/lib/judge/parse";

describe("extractJson", () => {
  it("parses a bare JSON object", () => {
    expect(extractJson('{"scores":[{"criterionId":"code","value":1}]}')).toEqual({
      scores: [{ criterionId: "code", value: 1 }],
    });
  });

  it("extracts JSON wrapped in a markdown code fence", () => {
    const text = 'Here is my verdict:\n```json\n{"scores":[{"criterionId":"look","value":0.5}]}\n```\nDone.';
    expect(extractJson(text)).toEqual({
      scores: [{ criterionId: "look", value: 0.5 }],
    });
  });

  it("ignores prose before and after the object", () => {
    const text =
      "I inspected the file and found issues. {\"scores\": [{\"criterionId\": \"compliance\", \"value\": 0}]} That's my assessment.";
    expect(extractJson(text)).toEqual({
      scores: [{ criterionId: "compliance", value: 0 }],
    });
  });

  it("is not confused by braces inside string values", () => {
    const text = '{"rationale":"uses a {placeholder} token","value":1}';
    expect(extractJson(text)).toEqual({
      rationale: "uses a {placeholder} token",
      value: 1,
    });
  });

  it("skips a non-JSON brace and finds the real object", () => {
    const text = 'function f() { return 1 } then: {"value": 2}';
    expect(extractJson(text)).toEqual({ value: 2 });
  });

  it("returns null when there is no JSON object", () => {
    expect(extractJson("no json here at all")).toBeNull();
  });

  it("skips a leading object that fails the predicate and finds the verdict", () => {
    const text =
      '{"status":"thinking"} then the verdict: {"scores":[{"criterionId":"code","value":1}]}';
    const hasScores = (o: unknown) => Array.isArray((o as { scores?: unknown })?.scores);
    expect(extractJson(text, hasScores)).toEqual({
      scores: [{ criterionId: "code", value: 1 }],
    });
  });

  it("returns null when no object satisfies the predicate", () => {
    const hasScores = (o: unknown) => Array.isArray((o as { scores?: unknown })?.scores);
    expect(extractJson('{"note":"no scores here"}', hasScores)).toBeNull();
  });
});
