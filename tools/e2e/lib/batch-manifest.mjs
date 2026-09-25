// Batch manifest: the conditions a set of e2e runs was measured under, written once per harness
// invocation next to results.jsonl. A pass-rate means little without the commit, the exact scenario
// content, the model build and the machine behind it; run.json does not carry those, and this file
// deliberately does not change run.json.
//
// Best effort by design: every probe (git, Ollama, GPU, the CLI's --print-config) may be missing,
// and each missing piece is recorded as null plus the reason. Nothing here may fail a run.
//
// Output: one JSON object appended as a line to <out>/manifest.jsonl. The harness reuses one
// E2E_OUT_DIR across invocations (gate.sh loops, model comparisons), so a single manifest.json
// would be overwritten; results.jsonl records carry the same `batch` id to join the two.
//
// CLI (all flags optional except --out and --repo):
//   node batch-manifest.mjs --out <dir> --repo <root> --batch-id <id> --harness <e2e-run.sh|e2e-run.ps1>
//     [--model M] [--ollama-endpoint URL] [--ollama-ctx N] [--max-cost X] [--auto-approve RX]
//     [--config k=v]... [--print-config-file F] [--print-config-error MSG] [--scenario <file>]...
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { appendFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync } from "node:fs";
import os from "node:os";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

// A config key whose value must never be written out. Wider than the CLI's own redaction on
// purpose: this file is meant to be shared with whoever compares the results.
const SECRET_KEY = /(api[_-]?key|secret|token|password|passwd|credential|authorization|auth[_-]?header|private[_-]?key)/i;
export const MASK = "***masked***";

/** Masks a value if its key looks sensitive, and user:password@ in any URL it contains. */
export function maskValue(key, value) {
  const v = value == null ? "" : String(value);
  if (SECRET_KEY.test(key) && v !== "") return MASK;
  return v.replace(/([a-z][a-z0-9+.-]*:\/\/)[^\s/@]+@/gi, `$1${MASK}@`);
}

/** `k=v` override list -> [{key, value}] with sensitive values masked. */
export function maskOverrides(list) {
  return (list ?? []).map((kv) => {
    const i = kv.indexOf("=");
    const key = i < 0 ? kv : kv.slice(0, i);
    const value = i < 0 ? "" : kv.slice(i + 1);
    return { key, value: maskValue(key, value) };
  });
}

/** Parses the CLI's --print-config text (`key = value  [override]`) into a masked object. */
export function parsePrintConfig(text) {
  const out = {};
  for (const raw of text.split(/\r?\n/)) {
    const m = /^([A-Za-z0-9_.-]+) = (.*?)(\s{2}\[override\])?$/.exec(raw);
    if (!m) continue;
    out[m[1]] = { value: maskValue(m[1], m[2]), override: Boolean(m[3]) };
  }
  return out;
}

function listFiles(dir) {
  const out = [];
  const walk = (d) => {
    for (const name of readdirSync(d)) {
      const p = join(d, name);
      if (statSync(p).isDirectory()) walk(p);
      else out.push(p);
    }
  };
  if (existsSync(dir) && statSync(dir).isDirectory()) walk(dir);
  return out;
}

/**
 * Content hash of everything a scenario hands to the agent and to the assertions: the scenario
 * JSON, its prompt or multi-agent file and every fixture file. Line endings are normalized so a
 * Windows and a Linux checkout of the same commit get the same version.
 */
export function scenarioVersion(scenarioPath) {
  const sdir = dirname(scenarioPath);
  const scenario = JSON.parse(readFileSync(scenarioPath, "utf8"));
  const files = [scenarioPath];
  for (const rel of [scenario.prompt_file, scenario.multi_agent]) {
    if (typeof rel === "string" && rel !== "") files.push(join(sdir, rel));
  }
  if (typeof scenario.fixture === "string" && scenario.fixture !== "") {
    files.push(...listFiles(join(sdir, scenario.fixture)).sort());
  }
  const h = createHash("sha256");
  for (const f of files) {
    const rel = relative(sdir, f).split("\\").join("/");
    const body = existsSync(f) ? readFileSync(f).toString("latin1").replace(/\r\n/g, "\n") : "<missing>";
    h.update(rel).update("\0").update(body, "latin1").update("\0");
  }
  return {
    id: scenario.id ?? null,
    file: relative(sdir, scenarioPath),
    sha256: h.digest("hex"),
    config: maskOverrides(Array.isArray(scenario.config) ? scenario.config : []),
    build_cmd: scenario.assert?.build_cmd ?? null,
  };
}

function tryExec(cmd, args, cwd) {
  try {
    return { value: execFileSync(cmd, args, { cwd, encoding: "utf8", timeout: 5000, stdio: ["ignore", "pipe", "ignore"] }).trim() };
  } catch (e) {
    return { value: null, error: e instanceof Error ? e.message.split("\n")[0] : String(e) };
  }
}

export function gitInfo(repo, exec = tryExec) {
  const commit = exec("git", ["rev-parse", "HEAD"], repo);
  if (commit.value === null) return { commit: null, dirty: null, reason: `git unavailable: ${commit.error}` };
  const status = exec("git", ["status", "--porcelain"], repo);
  return { commit: commit.value, dirty: status.value === null ? null : status.value !== "" };
}

export function hostInfo(exec = tryExec) {
  const cpus = os.cpus();
  const gpu = exec("nvidia-smi", ["--query-gpu=name,memory.total,driver_version", "--format=csv,noheader"]);
  return {
    platform: os.platform(),
    release: os.release(),
    arch: os.arch(),
    cpu_model: cpus[0]?.model ?? null,
    cpu_count: cpus.length,
    total_mem_bytes: os.totalmem(),
    node: process.version,
    gpu: gpu.value ? gpu.value.split(/\r?\n/) : null,
    gpu_reason: gpu.value ? undefined : "nvidia-smi not available on the harness host",
  };
}

async function fetchJson(url, body, timeoutMs = 5000) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      method: body === undefined ? "GET" : "POST",
      headers: body === undefined ? undefined : { "content-type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: ctl.signal,
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.json();
  } finally {
    clearTimeout(t);
  }
}

/**
 * What the Ollama server says about the model: server version, digest, quantization, template and
 * what is loaded right now (size_vram 0 means it fell back to CPU). Each part independently null
 * with a reason when unavailable.
 */
export async function ollamaInfo(model, endpoint, fetcher = fetchJson) {
  if (!model || !model.startsWith("ollama/")) {
    return { applicable: false, reason: "model is not served by Ollama (or no --model given)" };
  }
  const name = model.slice("ollama/".length);
  const base = (endpoint || "http://127.0.0.1:11434").replace(/\/+$/, "");
  const out = { applicable: true, endpoint: maskValue("endpoint", base), model: name };
  const errs = {};
  const attempt = async (label, fn) => {
    try {
      return await fn();
    } catch (e) {
      errs[label] = e instanceof Error ? e.message : String(e);
      return null;
    }
  };
  const version = await attempt("version", () => fetcher(`${base}/api/version`));
  out.server_version = version?.version ?? null;
  const show = await attempt("show", () => fetcher(`${base}/api/show`, { model: name }));
  out.details = show?.details ?? null;
  out.quantization = show?.details?.quantization_level ?? null;
  out.parameters = show?.parameters ?? null;
  out.template = show?.template ?? null;
  out.template_sha256 = typeof show?.template === "string" ? createHash("sha256").update(show.template).digest("hex") : null;
  const tags = await attempt("tags", () => fetcher(`${base}/api/tags`));
  const tag = (tags?.models ?? []).find((m) => m.name === name || m.model === name);
  out.digest = tag?.digest ?? null;
  if (tags && !tag) errs.tags = "model not listed by /api/tags";
  const ps = await attempt("ps", () => fetcher(`${base}/api/ps`));
  const loaded = (ps?.models ?? []).find((m) => m.name === name || m.model === name);
  out.loaded = loaded
    ? { size: loaded.size ?? null, size_vram: loaded.size_vram ?? null, context_length: loaded.context_length ?? null }
    : null;
  if (ps && !loaded) errs.ps = "model not loaded at manifest time";
  if (Object.keys(errs).length > 0) out.errors = errs;
  return out;
}

export async function buildManifest(input, probes = {}) {
  const exec = probes.exec ?? tryExec;
  const scenarios = [];
  for (const s of input.scenarios ?? []) {
    try {
      scenarios.push(scenarioVersion(s));
    } catch (e) {
      scenarios.push({ file: s, sha256: null, reason: e instanceof Error ? e.message : String(e) });
    }
  }
  let effectiveConfig = null;
  let effectiveConfigReason = input.printConfigError || null;
  if (input.printConfigFile) {
    try {
      const parsed = parsePrintConfig(readFileSync(input.printConfigFile, "utf8"));
      if (Object.keys(parsed).length > 0) effectiveConfig = parsed;
      else effectiveConfigReason = effectiveConfigReason ?? "--print-config produced no config lines";
    } catch (e) {
      effectiveConfigReason = e instanceof Error ? e.message : String(e);
    }
  } else if (!effectiveConfigReason) {
    effectiveConfigReason = "--print-config was not run";
  }
  return {
    manifest_version: 1,
    batch_id: input.batchId ?? null,
    created_at: (probes.now ?? (() => new Date()))().toISOString(),
    harness: input.harness ?? null,
    git: gitInfo(input.repo, exec),
    model: input.model || null,
    requested: {
      ollama_context_size: input.ollamaCtx ? Number(input.ollamaCtx) : null,
      ollama_endpoint: input.ollamaEndpoint ? maskValue("endpoint", input.ollamaEndpoint) : null,
      max_cost_usd: input.maxCost ? Number(input.maxCost) : null,
      auto_approve: input.autoApprove ?? null,
      // Every --config the harness passes to each run (the --ollama-* sugar included), masked.
      config_overrides: maskOverrides(input.configs),
    },
    ollama: await ollamaInfo(input.model, input.ollamaEndpoint, probes.fetcher),
    host: hostInfo(exec),
    // The resolved configuration the CLI reports with the same overrides, secrets masked. The
    // scenario-level `config` entries differ per scenario and are listed under scenarios[].
    effective_config: effectiveConfig,
    effective_config_reason: effectiveConfig ? undefined : effectiveConfigReason,
    scenarios,
  };
}

export function parseArgs(argv) {
  const input = { configs: [], scenarios: [] };
  const single = {
    "--out": "out",
    "--repo": "repo",
    "--batch-id": "batchId",
    "--harness": "harness",
    "--model": "model",
    "--ollama-endpoint": "ollamaEndpoint",
    "--ollama-ctx": "ollamaCtx",
    "--max-cost": "maxCost",
    "--auto-approve": "autoApprove",
    "--print-config-file": "printConfigFile",
    "--print-config-error": "printConfigError",
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const v = argv[i + 1];
    if (a in single) {
      input[single[a]] = v;
      i++;
    } else if (a === "--config") {
      input.configs.push(v);
      i++;
    } else if (a === "--scenario") {
      input.scenarios.push(v);
      i++;
    } else {
      throw new Error(`unknown argument: ${a}`);
    }
  }
  if (!input.out || !input.repo) throw new Error("--out and --repo are required");
  return input;
}

async function main(argv) {
  let input;
  try {
    input = parseArgs(argv);
  } catch (e) {
    console.error(`batch-manifest: ${e instanceof Error ? e.message : String(e)}`);
    return 2;
  }
  const manifest = await buildManifest(input);
  mkdirSync(input.out, { recursive: true });
  appendFileSync(join(input.out, "manifest.jsonl"), JSON.stringify(manifest) + "\n");
  return 0;
}

// Run as a script only, not when imported by the unit tests. Compared case-insensitively because
// Windows hands the same path over with either drive-letter case.
const self = resolve(fileURLToPath(import.meta.url)).toLowerCase();
if (process.argv[1] && resolve(process.argv[1]).toLowerCase() === self) {
  main(process.argv.slice(2)).then(
    (code) => {
      process.exitCode = code;
    },
    (e) => {
      console.error(`batch-manifest: ${e instanceof Error ? e.message : String(e)}`);
      process.exitCode = 1;
    },
  );
}
