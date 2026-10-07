// @vitest-environment node
import { describe, it, expect } from "vitest";
import { loadedModelFacts, parseModelParameters, servedModelFacts } from "@/lib/catalog/ollama-facts";

// Shapes as Ollama 0.33 returns them, trimmed to the fields that matter.
const tags = {
  models: [
    { name: "qwen3.6:27b", digest: "a50eda8ed977aa11bb22cc33dd44ee55ff6677889900aabbccddeeff00112233" },
    { name: "gpt-oss:20b", digest: "17052f91a42e00112233445566778899aabbccddeeff00112233445566778899" },
  ],
};
const show = {
  parameters: 'temperature                    1\ntop_k                          20\ntop_p                          0.95\nstop                           "<|im_end|>"\nstop                           "<|endoftext|>"',
};

describe("parseModelParameters", () => {
  it("reads numbers as numbers and collects a repeated key into a list", () => {
    expect(parseModelParameters(show.parameters)).toEqual({
      temperature: 1,
      top_k: 20,
      top_p: 0.95,
      stop: ["<|im_end|>", "<|endoftext|>"],
    });
  });

  it("returns nothing for a model that bakes in no parameters", () => {
    expect(parseModelParameters(undefined)).toEqual({});
    expect(parseModelParameters("")).toEqual({});
  });
});

// The digest is what tells two runs of "qwen3.6:27b" apart after the tag was pulled
// again, so it must come from the exact tag, never from a similarly named one.
describe("servedModelFacts", () => {
  it("records the short digest of the exact tag, the server version and the defaults", () => {
    expect(servedModelFacts("qwen3.6:27b", tags, show, { version: "0.33.3" })).toEqual({
      modelDigest: "a50eda8ed977",
      serverVersion: "0.33.3",
      generationParams: { temperature: 1, top_k: 20, top_p: 0.95, stop: ["<|im_end|>", "<|endoftext|>"] },
    });
  });

  it("leaves the digest out for a tag the server does not list", () => {
    const facts = servedModelFacts("qwen3.6", tags, show, { version: "0.33.3" });
    expect(facts.modelDigest).toBeUndefined();
    expect(facts.serverVersion).toBe("0.33.3");
  });
});

// Memory and GPU share are read after the run: a model that fell back to the CPU runs
// ten times slower and its timing is not comparable with a GPU run.
describe("loadedModelFacts", () => {
  const ps = {
    models: [
      { name: "qwen3.6:27b", size: 24_000_000_000, size_vram: 12_000_000_000, context_length: 65536 },
    ],
  };

  it("reports memory, the share on the GPU and the window the model is loaded with", () => {
    expect(loadedModelFacts("qwen3.6:27b", ps)).toEqual({
      memoryGb: 24,
      gpuShare: 0.5,
      loadedContextWindow: 65536,
    });
  });

  it("reports nothing when the model is no longer loaded", () => {
    expect(loadedModelFacts("gpt-oss:20b", ps)).toEqual({});
  });
});
