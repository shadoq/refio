// Interaction scenarios the judge evidence is built from. A step drives the page
// and returns a plain-words description of what it did, which lands in
// interactions.json so the judge knows what each screenshot was supposed to show.
import type { Locator, Page } from "playwright";
import { scenarioIdFor } from "../../../src/lib/judge/interactions";
import type { ScenarioId } from "../../../src/lib/judge/interactions";

// Steps get a freshly loaded page each time (see captureInteractions).
export type InteractionStep = (page: Page) => Promise<string>;

export interface Scenario {
  id: ScenarioId;
  steps: InteractionStep[];
}

// What counts as "a thing a user can click". Real controls come first: an in-page
// anchor only scrolls, so filling all three slots with nav links would prove
// nothing about whether the page works. Within each group the DOM order decides,
// so the choice is reproducible across runs and models.
const CONTROLS = 'button, [role="button"], input[type="submit"], summary, .btn';
const ANCHORS = 'a[href^="#"]';
const CLICKABLE = `${CONTROLS}, ${ANCHORS}`;
const CLICK_TIMEOUT_MS = 2000;
// Actionability probe per candidate; short, since it runs over the whole list.
const TRIAL_TIMEOUT_MS = 800;
const MAX_CANDIDATES = 25;

async function describe(el: Locator): Promise<string> {
  const label =
    (await el.getAttribute("aria-label")) ??
    ((await el.textContent()) ?? "").trim().replace(/\s+/g, " ").slice(0, 60);
  const tag = await el.evaluate((n) => n.tagName.toLowerCase());
  return label ? `<${tag}> "${label}"` : `an unlabelled <${tag}>`;
}

// Visible and enabled is not enough: a control inside a closed modal satisfies both
// while sitting under an overlay, and clicking it only times out. A trial click runs
// Playwright's full actionability check (visible, stable, receives pointer events)
// without firing the event, which is the same bar a real click has to clear.
async function isClickable(el: Locator): Promise<boolean> {
  if (!(await el.isVisible()) || !(await el.isEnabled())) return false;
  return el
    .click({ trial: true, timeout: TRIAL_TIMEOUT_MS })
    .then(() => true)
    .catch(() => false);
}

// Candidates in DOM order, real controls before in-page anchors: an anchor only
// scrolls, so filling every step with nav links would prove nothing about the page.
async function candidateList(page: Page): Promise<Locator[]> {
  const out: Locator[] = [];
  for (const selector of [CONTROLS, ANCHORS]) {
    const all = page.locator(selector);
    const count = Math.min(await all.count(), MAX_CANDIDATES);
    for (let i = 0; i < count; i++) out.push(all.nth(i));
  }
  return out;
}

// The nth control a user could actually click, skipping the ones that only look
// clickable. Deterministic: the same page always yields the same nth element.
async function nthClickable(page: Page, n: number): Promise<Locator | null> {
  let found = -1;
  for (const el of await candidateList(page)) {
    if (!(await isClickable(el))) continue;
    if (++found === n) return el;
  }
  return null;
}

// Default scenario: click the nth working control. Works for any artifact, including
// tasks added to the benchmark later with no scenario of their own.
function clickNth(n: number): InteractionStep {
  return async (page) => {
    const el = await nthClickable(page, n);
    if (!el) return `the page has no working interactive element #${n + 1}`;
    const what = await describe(el);
    await el.click({ timeout: CLICK_TIMEOUT_MS });
    await page.waitForTimeout(700);
    return `clicked ${what}`;
  };
}

const genericScenario: Scenario = {
  id: "generic",
  steps: [clickNth(0), clickNth(1), clickNth(2)],
};

// Snake is keyboard-driven: clicking alone never proves the game runs.
async function startSnake(page: Page): Promise<string> {
  const el = await nthClickable(page, 0);
  if (!el) return "no button to start the game";
  const what = await describe(el);
  await el.click({ timeout: CLICK_TIMEOUT_MS });
  await page.waitForTimeout(500);
  return `clicked ${what}`;
}

const snakeScenario: Scenario = {
  id: "snake",
  steps: [
    async (page) => `${await startSnake(page)} (start menu -> game)`,
    async (page) => {
      const started = await startSnake(page);
      await page.keyboard.press("ArrowRight");
      await page.waitForTimeout(1500);
      await page.keyboard.press("ArrowDown");
      // Long enough that a working snake has visibly moved across the grid.
      await page.waitForTimeout(3000);
      return `${started}, then pressed ArrowRight and ArrowDown and waited ~4.5s`;
    },
    async (page) => {
      const started = await startSnake(page);
      await page.waitForTimeout(2000);
      await page.keyboard.press("r");
      await page.waitForTimeout(800);
      return `${started}, then pressed R to restart`;
    },
  ],
};

// A todo's "done" control: a real checkbox, an ARIA checkbox, or the custom circle
// most implementations render inside the row.
const TODO_TOGGLE =
  'input[type="checkbox"], [role="checkbox"], ' +
  'li [class*="check" i], li [class*="toggle" i], li [class*="circle" i], li button';

// Todo needs input before anything is on screen.
async function addTodos(page: Page): Promise<string> {
  const input = page.locator('input[type="text"], input:not([type]), textarea').first();
  if (!(await input.count())) return "no text input to add a todo";
  for (const text of ["Buy milk", "Walk the dog"]) {
    await input.fill(text);
    await input.press("Enter");
    await page.waitForTimeout(300);
  }
  return 'added two todos ("Buy milk", "Walk the dog")';
}

const todoScenario: Scenario = {
  id: "todo",
  steps: [
    async (page) => addTodos(page),
    async (page) => {
      const added = await addTodos(page);
      // Few todo apps use a real checkbox; most render a custom circle or toggle,
      // so fall back to the first control inside the first list row.
      const box = page.locator(TODO_TOGGLE).first();
      if (!(await box.count())) return `${added}; nothing to toggle a todo with`;
      await box.click({ timeout: CLICK_TIMEOUT_MS });
      await page.waitForTimeout(400);
      return `${added}, then marked the first one complete`;
    },
    async (page) => {
      const added = await addTodos(page);
      const filter = page
        .locator(CLICKABLE)
        .filter({ hasText: /^\s*(active|completed)\s*$/i })
        .first();
      if (!(await filter.count())) return `${added}; no Active/Completed filter found`;
      const what = await describe(filter);
      await filter.click({ timeout: CLICK_TIMEOUT_MS });
      await page.waitForTimeout(400);
      return `${added}, then switched the filter via ${what}`;
    },
  ],
};

const SCENARIOS: Record<ScenarioId, Scenario> = {
  generic: genericScenario,
  snake: snakeScenario,
  todo: todoScenario,
};

export function scenarioFor(taskId: string): Scenario {
  return SCENARIOS[scenarioIdFor(taskId)];
}
