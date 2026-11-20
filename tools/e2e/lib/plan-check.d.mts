// Types for the plain-JS trusted planning check, so the TypeScript unit tests can import it.
export class ScenarioError extends Error {}
export interface PlanStep {
  index: number;
  title: string;
  text: string;
  status: string;
}
export interface Plan {
  steps: PlanStep[];
  text: string;
  from: string;
}
export function stepsFromText(text: string): PlanStep[];
export const MAIN_AGENT: unique symbol;
export function stepsFromTasksCalls(run: unknown, agent?: string | typeof MAIN_AGENT): PlanStep[] | null;
export function outputFor(run: unknown, agent?: string): string;
export function resolvePlan(spec: unknown, input: { run: unknown; planText: string | null }): Plan;
export function changedFiles(fixtureDir: string, projectDir: string): string[];
export function pathsIn(text: string): string[];
export function checkPlan(spec: any, plan: Plan, fixtureDir: string): string[];
export function checkFollowed(spec: any, plan: Plan, fixtureDir: string, projectDir: string): string[];
export function checkRounds(spec: any, run: any, projectDir: string): string[];
export function evaluate(
  scenario: unknown,
  input: { fixtureDir: string; projectDir: string; run?: unknown; planText?: string | null },
): string[];
