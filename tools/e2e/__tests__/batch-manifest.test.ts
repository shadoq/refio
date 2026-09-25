// The batch manifest is what makes two result sets comparable: same commit, same scenario content,
// same model build. These tests pin the properties that matter for that - secrets never land in the
// file, a scenario's version follows its content (and not the checkout's line endings), and an
// unreachable server or missing tool degrades to null plus a reason instead of failing the run.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import {
  buildManifest,
  MASK,
  maskOverrides,
  ollamaInfo,
  parsePrintConfig,
  scenarioVersion,
  type Exec,
} from "../lib/batch-manifest.mjs";

const here = fileURLToPath(new URL(".", import.meta.url));
const noTools: Exec = () => ({ value: null, error: "not installed" });

function scenarioDir(promptText = "Fix it.\n") {
  const root = mkdtempSync(join(tmpdir(), "manifest-"));
  mkdirSync(join(root, "fixtures", "demo", "src"), { recursive: true });
  mkdirSync(join(root, "prompts"));
  writeFileSync(join(root, "fixtures", "demo", "src", "a.js"), "module.exports = 1;\n");
  writeFileSync(join(root, "prompts", "demo.md"), promptText);
  const scenario = join(root, "demo.json");
  writeFileSync(
    scenario,
    JSON.stringify({
      id: "demo",
      fixture: "fixtures/demo",
      prompt_file: "prompts/demo.md",
      config: ["providers.openai.openai_api_key=sk-live-123", "agent.max_turn_minutes=5"],
      assert: { build_cmd: "node --test" },
    }),
  );
  return { root, scenario };
}

test("secrets in config overrides are masked, ordinary values kept", () => {
  const masked = maskOverrides([
    "providers.openai.openai_api_key=sk-live-123",
    "providers.x.auth_token=abc",
    "db.password=hunter2",
    "providers.ollama.ollama_endpoint=http://user:pw@box:11434",
    "providers.ollama.ollama_context_size=65536",
  ]);
  const text = JSON.stringify(masked);
  for (const secret of ["sk-live-123", "abc\"", "hunter2", "user:pw"]) assert.ok(!text.includes(secret), secret);
  assert.deepEqual(masked[4], { key: "providers.ollama.ollama_context_size", value: "65536" });
  assert.equal(masked[3].value, `http://${MASK}@box:11434`);
});

test("the CLI's print-config output is parsed and re-masked", () => {
  const parsed = parsePrintConfig(
    [
      "# Resolved Refio config (override > DB > YAML > default)",
      "agent.max_cost_usd = 0.5  [override]",
      "providers.custom.custom_password = plain-text-leak",
      "ui.selected_model = ollama/qwen3.5:9b",
      "",
    ].join("\n"),
  );
  assert.deepEqual(parsed["agent.max_cost_usd"], { value: "0.5", override: true });
  assert.equal(parsed["providers.custom.custom_password"].value, MASK);
  assert.equal(parsed["ui.selected_model"].override, false);
});

test("a scenario's version changes with its fixture, prompt or definition, not with line endings", () => {
  const { root, scenario } = scenarioDir();
  const v1 = scenarioVersion(scenario).sha256;
  writeFileSync(join(root, "prompts", "demo.md"), "Fix it.\r\n");
  assert.equal(scenarioVersion(scenario).sha256, v1, "CRLF checkout must not look like a new version");
  writeFileSync(join(root, "fixtures", "demo", "src", "a.js"), "module.exports = 2;\n");
  const v2 = scenarioVersion(scenario).sha256;
  assert.notEqual(v2, v1);
  writeFileSync(join(root, "prompts", "demo.md"), "Fix it properly.\n");
  assert.notEqual(scenarioVersion(scenario).sha256, v2);
});

test("an unreachable Ollama server yields nulls with reasons, never an exception", async () => {
  const info = await ollamaInfo("ollama/qwen3.5:9b", "http://127.0.0.1:9", async () => {
    throw new Error("connect ECONNREFUSED");
  });
  assert.equal(info.applicable, true);
  assert.equal(info.digest, null);
  assert.equal(info.quantization, null);
  assert.equal(info.server_version, null);
  assert.match(info.errors.show, /ECONNREFUSED/);
});

test("Ollama identity fields come from the server when it answers", async () => {
  const answers: Record<string, unknown> = {
    "/api/version": { version: "0.12.3" },
    "/api/show": { details: { quantization_level: "Q4_K_M", family: "qwen3" }, template: "{{ .Prompt }}", parameters: "num_ctx 65536" },
    "/api/tags": { models: [{ name: "qwen3.5:9b", digest: "sha256:abc" }] },
    "/api/ps": { models: [{ name: "qwen3.5:9b", size: 10, size_vram: 10, context_length: 65536 }] },
  };
  const info = await ollamaInfo("ollama/qwen3.5:9b", "http://box:11434", async (url) => answers[new URL(url).pathname]);
  assert.equal(info.server_version, "0.12.3");
  assert.equal(info.quantization, "Q4_K_M");
  assert.equal(info.digest, "sha256:abc");
  assert.equal(info.loaded.size_vram, 10);
  assert.equal(typeof info.template_sha256, "string");
  assert.equal(info.errors, undefined);
});

test("a non-Ollama model is marked not applicable instead of probed", async () => {
  let called = false;
  const info = await ollamaInfo("openrouter/x", undefined, async () => {
    called = true;
    return {};
  });
  assert.equal(info.applicable, false);
  assert.equal(called, false);
});

test("the whole manifest survives missing git, GPU and CLI, and carries no secret", async () => {
  const { scenario } = scenarioDir();
  const m = await buildManifest(
    {
      repo: "/nowhere",
      batchId: "b1",
      model: "ollama/qwen3.5:9b",
      ollamaCtx: "65536",
      configs: ["providers.ollama.ollama_context_size=65536", "providers.openai.openai_api_key=sk-live-123"],
      scenarios: [scenario, join(tmpdir(), "missing-scenario.json")],
      printConfigError: "CLI not built",
    },
    { exec: noTools, fetcher: async () => Promise.reject(new Error("offline")), now: () => new Date(0) },
  );
  assert.equal(m.batch_id, "b1");
  assert.equal(m.git.commit, null);
  assert.match(m.git.reason, /git unavailable/);
  assert.equal(m.host.gpu, null);
  assert.equal(m.requested.ollama_context_size, 65536);
  assert.equal(m.effective_config, null);
  assert.equal(m.effective_config_reason, "CLI not built");
  assert.equal(m.scenarios[0].id, "demo");
  assert.equal(m.scenarios[0].build_cmd, "node --test");
  assert.equal(m.scenarios[1].sha256, null);
  assert.ok(!JSON.stringify(m).includes("sk-live-123"));
});

test("the command-line entry point appends one line per invocation", () => {
  const { scenario } = scenarioDir();
  const out = mkdtempSync(join(tmpdir(), "manifest-out-"));
  const script = join(here, "..", "lib", "batch-manifest.mjs");
  const args = [script, "--out", out, "--repo", join(here, "..", "..", ".."), "--batch-id", "x", "--scenario", scenario];
  for (let i = 0; i < 2; i++) {
    const r = spawnSync(process.execPath, args, { encoding: "utf8" });
    assert.equal(r.status, 0, r.stderr);
  }
  const lines = readFileSync(join(out, "manifest.jsonl"), "utf8").trim().split("\n");
  assert.equal(lines.length, 2);
  assert.equal(JSON.parse(lines[0]).scenarios[0].id, "demo");
  assert.equal(spawnSync(process.execPath, [script, "--bogus"], { encoding: "utf8" }).status, 2);
});
