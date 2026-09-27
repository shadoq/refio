// Types for the plain-JS trusted check, so the TypeScript unit tests can import it.
export class ScenarioError extends Error {}
export interface Region {
  start: string;
  end?: string;
}
export function normalizeText(text: string): string;
export function checkRegion(original: string, actual: string, region: Region, label?: string): string | null;
export function jsonDiff(a: unknown, b: unknown, path?: string): string | null;
export function checkJsonExcept(originalText: string, actualText: string, allowed: string[], label?: string): string | null;
export function checkScenario(scenario: unknown, fixtureDir: string, projectDir: string): string[];
