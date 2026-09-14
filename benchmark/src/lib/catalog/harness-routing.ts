// Where an external coding agent gets its model from. The recorded model id decides:
// "ollama/<name>" means the agent must be pointed at the local Ollama endpoint, so the
// same model can be measured under Refio and under that agent and the two rows line up
// in the comparison. Anything else is the agent's own provider, untouched.
//
// Pure: it only decides what to set, the runner does the setting.
export interface HarnessRouting {
  // What the agent's CLI is told to run; undefined lets the agent keep its default.
  harnessModel: string | undefined;
  // Environment overrides; a key mapped to undefined must be REMOVED from the child's
  // environment.
  env: Record<string, string | undefined>;
  extraArgs: string[];
}

const OLLAMA_PREFIX = "ollama/";

export const NATIVE_TOOLS_MODES = ["auto", "always", "never"] as const;
export type NativeToolsMode = (typeof NATIVE_TOOLS_MODES)[number];
const OLLAMA_DEFAULT_PORT = 11434;

// Accepts a bare host, host:port, or a full URL, like the e2e harness does.
export function ollamaBaseUrl(host: string): string {
  const trimmed = host.trim().replace(/\/+$/, "");
  if (/^https?:\/\//.test(trimmed)) return trimmed;
  return trimmed.includes(":")
    ? `http://${trimmed}`
    : `http://${trimmed}:${OLLAMA_DEFAULT_PORT}`;
}

const LOCAL_HOSTS = ["localhost", "127.0.0.1", "::1", "0.0.0.0"];

// Whether this host is the machine Codex already talks to when left alone.
function isLocalHost(host: string): boolean {
  const bare = host.trim().replace(/^https?:\/\//, "").replace(/\/.*$/, "").split(":")[0];
  return LOCAL_HOSTS.includes(bare);
}

// Ollama resolves a bare name to its "latest" tag; a client that reads the name as a
// download target does not.
function taggedModelName(model: string): string {
  return model.includes(":") ? model : `${model}:latest`;
}

export interface RoutingOptions {
  // Whether the model may reason before answering. "unknown" (the default) leaves the
  // agent's own behaviour untouched, which is not the same as asking for it to be on.
  thinking?: "on" | "off" | "unknown";
  // Ceiling on one message. A local model that reasons without bound otherwise spends
  // the whole time budget on a single answer it never finishes.
  maxOutputTokens?: number;
}

// How much room the model is given to work in. Refio sends this to Ollama explicitly;
// an agent talking to the same endpoint through a compatibility layer sends nothing and
// gets the server's default. Two harnesses on "the same local model" were therefore
// running with different windows, and nothing in the data said so. A sweep that must
// compare harnesses should point them at a model that carries the window itself.
export const DEFAULT_OLLAMA_CONTEXT = 65536;

// The command that loads a model with a given window, so the first real request does
// not pay for the load.
//
// It does NOT pin the window. Ollama reloads the model whenever a request asks for
// different options, and a client that sends none gets the server default - measured
// on Ollama 0.34: warm up at 65536, send one plain request, and the model comes back
// at 32768. The only way to give every agent the same window is to put it in the model
// itself ("PARAMETER num_ctx" in a Modelfile), because a model carries its own default
// into a request that asks for nothing.
export function ollamaWarmUpRequest(
  model: string,
  contextWindow: number,
): { url: string; body: unknown } {
  return {
    url: "/api/generate",
    body: {
      model,
      prompt: "warmup",
      options: { num_ctx: contextWindow, num_predict: 8 },
      stream: false,
      keep_alive: "30m",
    },
  };
}

// What each agent was allowed to do without asking. Not equivalent between agents, so
// recording it is the difference between a comparison and a guess.
const PERMISSION_MODE: Record<string, string> = {
  refio: "headless-auto",
  "claude-code": "acceptEdits",
  codex: "workspace-write",
  "gemini-cli": "auto_edit",
};

export function permissionModeOf(harnessId: string): string | undefined {
  return PERMISSION_MODE[harnessId];
}

// Only Claude Code exposes these as environment switches. Asking any other harness for
// them must fail rather than silently measure a setting that never took effect.
function generationEnv(harnessId: string, options: RoutingOptions): Record<string, string> {
  const env: Record<string, string> = {};
  const wantsThinkingChange = options.thinking === "on" || options.thinking === "off";
  const wantsCap = options.maxOutputTokens !== undefined;
  if (!wantsThinkingChange && !wantsCap) return env;

  if (harnessId !== "claude-code") {
    throw new Error(
      `${harnessId} cannot switch reasoning or cap its output from here; run it with its own flags and record the setting by hand`,
    );
  }
  if (options.thinking === "off") env.CLAUDE_CODE_DISABLE_THINKING = "1";
  if (options.maxOutputTokens !== undefined) {
    env.CLAUDE_CODE_MAX_OUTPUT_TOKENS = String(options.maxOutputTokens);
  }
  return env;
}

export function resolveHarnessRouting(
  harnessId: string,
  modelId: string,
  harnessModel: string | undefined,
  ollamaHost: string,
  options: RoutingOptions = {},
): HarnessRouting {
  const generation = generationEnv(harnessId, options);

  if (!modelId.startsWith(OLLAMA_PREFIX)) {
    return { harnessModel, env: { ...generation }, extraArgs: [] };
  }

  const localModel = harnessModel ?? modelId.slice(OLLAMA_PREFIX.length);
  const base = ollamaBaseUrl(ollamaHost);

  if (harnessId === "claude-code") {
    // Claude Code talks to whatever speaks the Anthropic protocol at this base url.
    // The user's own key is removed rather than left in place: with both set, the
    // cloud key wins and the run would silently measure a different model.
    return {
      harnessModel: localModel,
      env: {
        ANTHROPIC_BASE_URL: base,
        ANTHROPIC_AUTH_TOKEN: "ollama",
        ANTHROPIC_API_KEY: undefined,
        ...generation,
      },
      extraArgs: [],
    };
  }

  if (harnessId === "codex") {
    // Codex treats "ollama" as a reserved built-in provider and refuses to start at all
    // when its block is overridden, so the endpoint it uses is the one it assumes. A run
    // asked for a different host must fail here rather than quietly measure this machine.
    if (!isLocalHost(ollamaHost)) {
      throw new Error(
        `codex cannot be pointed at ${ollamaHost}: it reserves the ollama provider block, so it only reaches the local endpoint`,
      );
    }
    // Without a tag Codex reads the name as something to download and dies on a model
    // that exists only on this machine, which is every model built to carry its own
    // context window.
    return {
      harnessModel: taggedModelName(localModel),
      env: { ...generation },
      extraArgs: ["--oss", "--local-provider", "ollama"],
    };
  }

  throw new Error(
    `${harnessId} has no local model provider; use a native model id for it instead of ${modelId}`,
  );
}

// Config overrides the Refio CLI needs so it measures the same machine an external
// agent was pointed at. Refio reads its endpoint from the user's config file, so
// without this the two harnesses can quietly run on different hardware and every
// duration in the comparison becomes meaningless.
export function refioConfigOverrides(
  modelId: string,
  ollamaHost: string,
  contextWindow?: number,
  // Which tool channel Refio must use. Left out, Refio decides from its own registry of
  // model names - and a model built locally to carry its own context window is not in
  // it, so "auto" reads it as a model with no function calling and drops Refio onto the
  // JSON-envelope-in-prose path. The external agents consult no registry, so a sweep
  // that says nothing here measures Refio on a different mechanism than its rivals.
  nativeTools?: NativeToolsMode,
): string[] {
  if (!modelId.startsWith(OLLAMA_PREFIX)) return [];
  const overrides = [`providers.ollama.ollama_endpoint=${ollamaBaseUrl(ollamaHost)}`];
  // Pinned rather than left to the user's config file: a comparison whose two sides
  // read different windows measures the setup, not the agents.
  if (contextWindow !== undefined) {
    overrides.push(`providers.ollama.ollama_context_size=${contextWindow}`);
  }
  if (nativeTools !== undefined) {
    overrides.push(`tools.native_tools=${nativeTools}`);
  }
  return overrides;
}

// The model name Ollama itself knows, for a recorded id of the form "ollama/<name>".
// Null for anything that is not a local model.
export function ollamaModelName(modelId: string): string | null {
  return modelId.startsWith(OLLAMA_PREFIX) ? modelId.slice(OLLAMA_PREFIX.length) : null;
}
