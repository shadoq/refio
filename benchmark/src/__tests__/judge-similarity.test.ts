// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  tokenize,
  codeSimilarity,
  averagePairwiseSimilarity,
} from "@/lib/judge/similarity";

describe("tokenize", () => {
  it("splits on any whitespace and lowercases", () => {
    expect(tokenize("Foo   bar\nBAZ\tqux")).toEqual(["foo", "bar", "baz", "qux"]);
  });
});

describe("codeSimilarity", () => {
  it("is 1.0 for identical text", () => {
    const html = "<div>hello world</div>";
    expect(codeSimilarity(html, html)).toBe(1);
  });

  it("is near 0 for disjoint token sets", () => {
    expect(codeSimilarity("alpha beta gamma", "one two three")).toBe(0);
  });

  it("is between 0 and 1 for partial overlap", () => {
    // tokens {a,b,c} vs {b,c,d}: intersection 2, union 4 -> 0.5
    expect(codeSimilarity("a b c", "b c d")).toBeCloseTo(0.5);
  });

  it("treats two empty texts as identical", () => {
    expect(codeSimilarity("", "")).toBe(1);
  });
});

describe("averagePairwiseSimilarity", () => {
  it("is 1.0 for a single text", () => {
    expect(averagePairwiseSimilarity(["only one"])).toBe(1);
  });

  it("is 1.0 when all attempts are identical", () => {
    expect(averagePairwiseSimilarity(["x y", "x y", "x y"])).toBe(1);
  });

  it("averages similarity across all pairs", () => {
    // pairs: (ab,ab)=1, (ab,cd)=0, (ab,cd)=0 -> mean 1/3
    expect(averagePairwiseSimilarity(["a b", "a b", "c d"])).toBeCloseTo(1 / 3);
  });
});
