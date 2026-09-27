// Types for the plain-JS batch manifest writer, so the TypeScript unit tests can import it.
export const MASK: string;
export interface ExecResult {
  value: string | null;
  error?: string;
}
export type Exec = (cmd: string, args: string[], cwd?: string) => ExecResult;
export type Fetcher = (url: string, body?: unknown) => Promise<any>;
export interface ManifestInput {
  out?: string;
  repo: string;
  batchId?: string;
  harness?: string;
  model?: string;
  ollamaEndpoint?: string;
  ollamaCtx?: string;
  maxCost?: string;
  autoApprove?: string;
  configs?: string[];
  scenarios?: string[];
  printConfigFile?: string;
  printConfigError?: string;
}
export function maskValue(key: string, value: unknown): string;
export function maskOverrides(list: string[] | undefined): { key: string; value: string }[];
export function parsePrintConfig(text: string): Record<string, { value: string; override: boolean }>;
export function scenarioVersion(scenarioPath: string): {
  id: string | null;
  file: string;
  sha256: string;
  config: { key: string; value: string }[];
  build_cmd: string | null;
};
export function gitInfo(repo: string, exec?: Exec): { commit: string | null; dirty: boolean | null; reason?: string };
export function hostInfo(exec?: Exec): Record<string, unknown>;
export function ollamaInfo(model: string | undefined, endpoint: string | undefined, fetcher?: Fetcher): Promise<Record<string, any>>;
export function buildManifest(
  input: ManifestInput,
  probes?: { exec?: Exec; fetcher?: Fetcher; now?: () => Date },
): Promise<Record<string, any>>;
export function parseArgs(argv: string[]): ManifestInput;
