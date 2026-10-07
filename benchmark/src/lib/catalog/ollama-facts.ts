// Pure readers of Ollama's API responses: what exactly was served for a run, and how
// it sat in memory afterwards. The fetching lives in scripts/catalog/lib/run-context.ts;
// keeping the parsing here makes it testable without a server.

export type GenerationParams = Record<string, number | string | string[]>;

export interface ServedModelFacts {
  modelDigest?: string;
  serverVersion?: string;
  generationParams?: GenerationParams;
}

export interface LoadedModelFacts {
  memoryGb?: number;
  gpuShare?: number;
  loadedContextWindow?: number;
}

const DIGEST_CHARS = 12;

// The `parameters` text of /api/show: one "key value" pair per line, a key repeated
// for a list (stop sequences), string values in double quotes.
export function parseModelParameters(text: string | undefined): GenerationParams {
  const out: GenerationParams = {};
  for (const line of (text ?? "").split("\n")) {
    const m = /^(\S+)\s+(.+)$/.exec(line.trim());
    if (!m) continue;
    const raw = m[2].trim();
    const value = /^".*"$/.test(raw)
      ? raw.slice(1, -1)
      : Number.isFinite(Number(raw))
        ? Number(raw)
        : raw;
    const prev = out[m[1]];
    if (prev === undefined) out[m[1]] = value;
    else out[m[1]] = [...(Array.isArray(prev) ? prev : [String(prev)]), String(value)];
  }
  return out;
}

// What the server had under this exact tag when the run started: the weights' digest,
// the server version, and the sampling defaults baked into the model. A harness may
// override some of the defaults per request; these are what it overrides.
export function servedModelFacts(
  model: string,
  tags: { models?: Array<{ name: string; digest?: string }> } | null,
  show: { parameters?: string } | null,
  version: { version?: string } | null,
): ServedModelFacts {
  const digest = tags?.models?.find((m) => m.name === model)?.digest;
  const params = parseModelParameters(show?.parameters);
  return {
    ...(digest ? { modelDigest: digest.slice(0, DIGEST_CHARS) } : {}),
    ...(version?.version ? { serverVersion: version.version } : {}),
    ...(Object.keys(params).length > 0 ? { generationParams: params } : {}),
  };
}

// How the model sat in memory once the run finished (/api/ps): total size, the share
// of it on the GPU, and the context window it was actually loaded with. Empty when the
// model has already been unloaded.
export function loadedModelFacts(
  model: string,
  ps: {
    models?: Array<{ name: string; size?: number; size_vram?: number; context_length?: number }>;
  } | null,
): LoadedModelFacts {
  const loaded = ps?.models?.find((m) => m.name === model);
  if (!loaded) return {};
  const size = loaded.size ?? 0;
  return {
    ...(size > 0 ? { memoryGb: Math.round(size / 1e8) / 10 } : {}),
    ...(size > 0 && loaded.size_vram !== undefined
      ? { gpuShare: Math.round((loaded.size_vram / size) * 100) / 100 }
      : {}),
    ...(loaded.context_length ? { loadedContextWindow: loaded.context_length } : {}),
  };
}
