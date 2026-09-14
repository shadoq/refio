// @vitest-environment node
import { describe, it, expect } from "vitest";
import { resolveHarnessRouting, refioConfigOverrides } from "@/lib/catalog/harness-routing";

describe("resolveHarnessRouting for a cloud model", () => {
  it("changes nothing and passes the harness model through", () => {
    const r = resolveHarnessRouting("claude-code", "anthropic/claude-opus-5", "opus", "127.0.0.1");
    expect(r).toEqual({ harnessModel: "opus", env: {}, extraArgs: [] });
  });

  it("lets the agent pick its own model when none was given", () => {
    expect(resolveHarnessRouting("codex", "openai/gpt-5", undefined, "127.0.0.1").harnessModel)
      .toBeUndefined();
  });
});

describe("resolveHarnessRouting for an ollama model", () => {
  it("points Claude Code at the local endpoint and drops the cloud key", () => {
    const r = resolveHarnessRouting("claude-code", "ollama/qwen3.8:27b", undefined, "192.168.5.60");
    expect(r.harnessModel).toBe("qwen3.8:27b");
    expect(r.env).toEqual({
      ANTHROPIC_BASE_URL: "http://192.168.5.60:11434",
      ANTHROPIC_AUTH_TOKEN: "ollama",
      ANTHROPIC_API_KEY: undefined,
    });
    expect(r.extraArgs).toEqual([]);
  });

  it("keeps a host that already names its scheme and port", () => {
    const r = resolveHarnessRouting("claude-code", "ollama/x", undefined, "http://x:9999");
    expect(r.env.ANTHROPIC_BASE_URL).toBe("http://x:9999");
  });

  it("accepts a bare host with a port", () => {
    const r = resolveHarnessRouting("claude-code", "ollama/x", undefined, "dgx:11500");
    expect(r.env.ANTHROPIC_BASE_URL).toBe("http://dgx:11500");
  });

  it("switches Codex to its local provider through flags, not the environment", () => {
    const r = resolveHarnessRouting("codex", "ollama/qwen3.8:27b", undefined, "127.0.0.1");
    expect(r.harnessModel).toBe("qwen3.8:27b");
    expect(r.env).toEqual({});
    expect(r.extraArgs).toContain("--oss");
    expect(r.extraArgs).toContain("--local-provider");
  });

  // Codex reserves the built-in provider ids and refuses to start when one of them is
  // overridden, so the only host it can be given is the one it already assumes. A run
  // pointed elsewhere must say so rather than quietly measure the local machine.
  it("does not override the built-in provider for Codex", () => {
    const r = resolveHarnessRouting("codex", "ollama/qwen3.8:27b", undefined, "127.0.0.1");
    expect(r.extraArgs.join(" ")).not.toContain("model_providers");
  });

  it("refuses to run Codex against a host it cannot be pointed at", () => {
    expect(() => resolveHarnessRouting("codex", "ollama/qwen3.8:27b", undefined, "dgx")).toThrow(
      /cannot be pointed at/,
    );
  });

  // Without a tag Codex treats the name as one to download and dies on a model that
  // only exists locally, which is every model built to carry its own context window.
  it("gives Codex a fully tagged model name", () => {
    expect(
      resolveHarnessRouting("codex", "ollama/qwen3.5-9b-ctx64k", undefined, "127.0.0.1")
        .harnessModel,
    ).toBe("qwen3.5-9b-ctx64k:latest");
    expect(
      resolveHarnessRouting("codex", "ollama/qwen3.8:27b", undefined, "127.0.0.1").harnessModel,
    ).toBe("qwen3.8:27b");
  });

  it("lets an explicit harness model win over the name in the model id", () => {
    const r = resolveHarnessRouting("claude-code", "ollama/qwen3.8:27b", "qwen3.8:8b", "dgx");
    expect(r.harnessModel).toBe("qwen3.8:8b");
  });

  it("refuses an agent that cannot talk to a local provider", () => {
    expect(() => resolveHarnessRouting("gemini-cli", "ollama/x", undefined, "dgx")).toThrow(
      /local model provider/,
    );
  });
});

// The thinking setting changes what the model IS, so a harness that cannot express it
// must say so loudly rather than let the caller believe it took effect.
describe("resolveHarnessRouting and the thinking setting", () => {
  it("switches reasoning off for Claude Code through the environment", () => {
    const r = resolveHarnessRouting("claude-code", "ollama/qwen3.8:27b", undefined, "dgx", {
      thinking: "off",
    });
    expect(r.env.CLAUDE_CODE_DISABLE_THINKING).toBe("1");
  });

  it("leaves the agent's own default alone when nothing was asked", () => {
    const r = resolveHarnessRouting("claude-code", "ollama/x", undefined, "dgx");
    expect(r.env.CLAUDE_CODE_DISABLE_THINKING).toBeUndefined();
    expect("CLAUDE_CODE_DISABLE_THINKING" in r.env).toBe(false);
  });

  it("does not set the flag when reasoning is explicitly wanted", () => {
    const r = resolveHarnessRouting("claude-code", "anthropic/claude-opus-5", "opus", "dgx", {
      thinking: "on",
    });
    expect("CLAUDE_CODE_DISABLE_THINKING" in r.env).toBe(false);
  });

  it("refuses a thinking setting for a harness that cannot apply it", () => {
    expect(() =>
      resolveHarnessRouting("codex", "openai/gpt-5", undefined, "dgx", { thinking: "off" }),
    ).toThrow(/cannot switch reasoning/);
  });

  // A local model that reasons without bound eats the whole time budget in one message,
  // which is what the cap exists for.
  it("passes an output cap to Claude Code when one is set", () => {
    const r = resolveHarnessRouting("claude-code", "ollama/x", undefined, "dgx", {
      maxOutputTokens: 8192,
    });
    expect(r.env.CLAUDE_CODE_MAX_OUTPUT_TOKENS).toBe("8192");
  });
});

// The benchmark compares harnesses, so both must talk to the SAME machine. Refio takes
// its endpoint from its own config file, which silently pointed it at a different box
// than the one the external agent was given.
describe("refioConfigOverrides", () => {
  it("points the Refio CLI at the same endpoint the external agents get", () => {
    expect(refioConfigOverrides("ollama/qwen3.5:9b", "192.168.5.60")).toEqual([
      "providers.ollama.ollama_endpoint=http://192.168.5.60:11434",
    ]);
  });

  it("normalizes a host that already names a port", () => {
    expect(refioConfigOverrides("ollama/x", "dgx:11500")[0]).toContain("http://dgx:11500");
  });

  it("leaves a cloud model alone", () => {
    expect(refioConfigOverrides("anthropic/claude-opus-5", "192.168.5.60")).toEqual([]);
  });
});

// Refio picks its tool channel from a registry of model names. A model built locally to
// carry its own context window is not in that registry, so "auto" reads it as a model
// with no function calling and drops Refio onto the weakest path - the one where the
// model has to spell out a JSON envelope in prose. The external agents consult no such
// registry, so the same sweep measured Refio on a different mechanism and said nothing
// about it. The sweep therefore states the channel, and the choice is recorded.
describe("refioConfigOverrides and the tool channel", () => {
  it("leaves the channel to Refio when the sweep does not state one", () => {
    const o = refioConfigOverrides("ollama/qwen3.5:9b", "127.0.0.1");
    expect(o.join(" ")).not.toContain("native_tools");
  });

  it("pins the channel when the sweep states one", () => {
    expect(refioConfigOverrides("ollama/qwen3.5-9b-ctx64k", "127.0.0.1", 65536, "always")).toEqual([
      "providers.ollama.ollama_endpoint=http://127.0.0.1:11434",
      "providers.ollama.ollama_context_size=65536",
      "tools.native_tools=always",
    ]);
  });

  // A cloud model is not run through the Refio CLI's local provider at all, so there is
  // nothing to override and nothing to state.
  it("states nothing for a model that is not local", () => {
    expect(refioConfigOverrides("anthropic/claude-opus-5", "127.0.0.1", 65536, "always")).toEqual([]);
  });
});
