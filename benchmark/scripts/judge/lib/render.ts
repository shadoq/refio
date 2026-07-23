// Headless rendering for the judge evidence folder: timed viewport screenshots,
// a whole-page screenshot, and one screenshot per interaction step.
// Nothing here throws on a render failure - the reason is returned so a broken
// artifact gets judged as broken instead of aborting the run.
import { chromium } from "playwright";
import type { Browser, Page } from "playwright";
import { pathToFileURL } from "node:url";
import type { Scenario } from "./interactions";
import type { InteractionRecord } from "../../../src/lib/judge/interactions";
import { withDeadline } from "../../../src/lib/judge/deadline";

const NAV_TIMEOUT_MS = 30_000;
// Generous screenshot budget: heavy requestAnimationFrame artifacts (e.g. the
// neuron growth simulation) can starve the compositor and miss the 30s default.
const SCREENSHOT_TIMEOUT_MS = 90_000;
// Hard backstop for a whole capture call. Some page operations have no timeout of
// their own (keyboard input, page.evaluate), so a runaway rAF artifact can hang one
// of them indefinitely - one such artifact stalled the queue for 20+ minutes. This
// is well above any legitimate render (~30s) yet bounds the pathological case.
const RENDER_DEADLINE_MS = 180_000;
const VIEWPORT = { width: 1280, height: 800 };

// Milliseconds after load for each viewport screenshot. The 6s and 12s shots are
// what tell a live simulation apart from a first frame that froze.
export const MOTION_DELAYS_MS = [1000, 6000, 12000];
// Single settled shot used by the stability judge, which compares attempts rather
// than looking for motion.
export const SETTLED_SHOT_MS = 6000;

export interface RenderOutcome {
  consoleErrors: string[];
  // Non-null when navigation or a screenshot failed. The artifact is then treated
  // as a broken sample (judged low) rather than skipped.
  renderError: string | null;
}

function attachConsoleCapture(page: Page, sink: string[]): void {
  page.on("pageerror", (e) => sink.push(String(e)));
  page.on("console", (m) => {
    if (m.type() === "error") sink.push(m.text());
  });
}

async function openArtifact(
  browser: Browser,
  htmlPath: string,
  consoleErrors: string[],
): Promise<{ page: Page; navError: string | null }> {
  const page = await browser.newPage({ viewport: VIEWPORT });
  attachConsoleCapture(page, consoleErrors);
  try {
    await page.goto(pathToFileURL(htmlPath).href, {
      waitUntil: "load",
      timeout: NAV_TIMEOUT_MS,
    });
    return { page, navError: null };
  } catch (e) {
    return { page, navError: `navigation failed: ${(e as Error).message}` };
  }
}

// Best-effort teardown. When a capture is abandoned on deadline the page is still
// frozen, so close() may reject; the browser process is killed regardless, which is
// what prevents a leaked chrome-headless-shell.
async function closeQuietly(browser: Browser): Promise<void> {
  try {
    await browser.close();
  } catch {
    // Nothing actionable: the process is torn down even when close() rejects.
  }
}

async function shoot(page: Page, path: string, fullPage: boolean): Promise<void> {
  await page.screenshot({
    path,
    fullPage,
    animations: "disabled",
    timeout: SCREENSHOT_TIMEOUT_MS,
  });
}

// Scroll the whole page before a full-page shot: reveal-on-scroll sections
// (IntersectionObserver or scroll listeners) stay hidden otherwise, and the shot
// would show blank gaps the artifact does not actually have.
async function scrollThroughPage(page: Page): Promise<void> {
  await page.evaluate(async () => {
    const step = window.innerHeight;
    for (let y = 0; y < document.body.scrollHeight; y += step) {
      window.scrollTo(0, y);
      await new Promise((r) => setTimeout(r, 120));
    }
    window.scrollTo(0, document.body.scrollHeight);
    await new Promise((r) => setTimeout(r, 300));
    window.scrollTo(0, 0);
  });
  await page.waitForTimeout(500);
}

// Capture the timed viewport shots and, when asked, the whole scrollable page.
// `motionPaths` and `delaysMs` are positional pairs; extra paths are ignored.
export async function captureShots(
  htmlPath: string,
  opts: { motionPaths: string[]; delaysMs?: number[]; fullPagePath?: string },
): Promise<RenderOutcome> {
  const delays = opts.delaysMs ?? MOTION_DELAYS_MS;
  const browser = await chromium.launch();
  const consoleErrors: string[] = [];

  const run = async (): Promise<RenderOutcome> => {
    const { page, navError } = await openArtifact(browser, htmlPath, consoleErrors);
    let renderError = navError;

    let elapsed = 0;
    for (let i = 0; i < opts.motionPaths.length && i < delays.length; i++) {
      await page.waitForTimeout(Math.max(0, delays[i] - elapsed));
      elapsed = delays[i];
      try {
        await shoot(page, opts.motionPaths[i], false);
      } catch (e) {
        renderError = renderError ?? `shot-${i + 1} failed: ${(e as Error).message}`;
      }
    }

    if (opts.fullPagePath) {
      try {
        await scrollThroughPage(page);
        await shoot(page, opts.fullPagePath, true);
      } catch (e) {
        renderError = renderError ?? `shot-full failed: ${(e as Error).message}`;
      }
    }
    return { consoleErrors, renderError };
  };

  try {
    const { result } = await withDeadline(run(), RENDER_DEADLINE_MS, () => ({
      consoleErrors,
      renderError: `render exceeded ${RENDER_DEADLINE_MS}ms deadline`,
    }));
    return result;
  } finally {
    await closeQuietly(browser);
  }
}

// Playwright failures carry a multi-line, ANSI-coloured call log. Only the first
// line is useful to the judge; the rest would bloat interactions.json and the prompt.
function briefError(e: Error): string {
  // eslint-disable-next-line no-control-regex
  return e.message.split("\n")[0].replace(/\[\d+m/g, "").slice(0, 200);
}

// Run each scenario step in its own freshly loaded page, so every interaction shot
// shows the effect of that one step and not a pile-up of earlier modals.
export async function captureInteractions(
  htmlPath: string,
  scenario: Scenario,
  shotPaths: string[],
): Promise<{ records: InteractionRecord[]; consoleErrors: string[] }> {
  const browser = await chromium.launch();
  const consoleErrors: string[] = [];
  const records: InteractionRecord[] = [];
  const artifactUrl = pathToFileURL(htmlPath).href;

  const run = async (): Promise<void> => {
    for (let i = 0; i < scenario.steps.length && i < shotPaths.length; i++) {
      const shotName = shotPaths[i].split(/[\\/]/).pop() ?? shotPaths[i];
      const { page, navError } = await openArtifact(browser, htmlPath, consoleErrors);
      if (navError) {
        records.push({ shot: shotName, action: "not run", ok: false, note: navError });
        await page.close();
        continue;
      }
      let action = "not run";
      let ok = true;
      let note: string | undefined;
      try {
        action = await scenario.steps[i](page);
      } catch (e) {
        ok = false;
        note = briefError(e as Error);
      }
      // A click that leaves the artifact is itself a finding (dead or external
      // link), so report it rather than hiding it. An in-page anchor only adds a
      // hash and stays on the same document, which is normal navigation.
      const landedOn = page.url().split("#")[0];
      if (ok && landedOn !== artifactUrl) {
        note = `the page navigated away to ${page.url()}`;
      }
      try {
        await shoot(page, shotPaths[i], false);
      } catch (e) {
        ok = false;
        note = note ?? `screenshot failed: ${(e as Error).message}`;
      }
      records.push({ shot: shotName, action, ok, note });
      await page.close();
    }
  };

  try {
    const { timedOut } = await withDeadline(run(), RENDER_DEADLINE_MS, () => undefined);
    // A frozen artifact can hang a step (keyboard input on a stalled page has no
    // timeout of its own); record it so the judge sees why later shots are missing.
    if (timedOut) {
      records.push({
        shot: "(interactions)",
        action: "not run",
        ok: false,
        note: `interaction capture exceeded ${RENDER_DEADLINE_MS}ms deadline`,
      });
    }
    return { records, consoleErrors };
  } finally {
    await closeQuietly(browser);
  }
}
