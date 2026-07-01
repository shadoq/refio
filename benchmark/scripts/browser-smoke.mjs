#!/usr/bin/env node
// browser-smoke.mjs - deterministic runtime smoke test for a generated browser artifact (docs/0071
// §8.5 layer 2). Renders the artifact in headless Chromium (Playwright) and mechanically checks it:
//   - the page loads with NO unhandled JS error / console error (when no_js_errors),
//   - every required DOM selector exists after init (dom_present[]),
//   - each scripted interaction changes state (interactions[]: click/press then expect_* on a selector).
//
// This is the HARD, deterministic counterpart to the SOFT LLM judge: "SUCCESS" must also mean "it runs".
//
// Usage:  node browser-smoke.mjs <scenario.json> <project-dir>
// Exit:   0 = all smoke checks passed · 1 = a check failed · 2 = could not run (no playwright/browser).
// Reads the scenario's `smoke` block:
//   { "entry": "snake.html", "no_js_errors": true,
//     "dom_present": ["#score", "canvas"],
//     "interactions": [ { "press": "ArrowRight", "expect_text_change": "#score" },
//                       { "click": "#start", "expect_contains": { "selector": "#status", "text": "Playing" } } ] }
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { join, isAbsolute } from 'node:path';

const [, , scenarioPath, projectDir] = process.argv;
if (!scenarioPath || !projectDir) {
  console.error('usage: node browser-smoke.mjs <scenario.json> <project-dir>');
  process.exit(2);
}

let chromium;
try {
  ({ chromium } = await import('playwright'));
} catch {
  console.error('browser-smoke: playwright not installed (run: npm i -D playwright && npx playwright install chromium)');
  process.exit(2);
}

const scenario = JSON.parse(readFileSync(scenarioPath, 'utf8'));
const smoke = scenario.smoke;
if (!smoke || !smoke.entry) {
  console.error('browser-smoke: scenario has no smoke.entry');
  process.exit(2);
}

const entryPath = isAbsolute(smoke.entry) ? smoke.entry : join(projectDir, smoke.entry);
const failures = [];

let browser;
try {
  browser = await chromium.launch();
} catch (e) {
  console.error(`browser-smoke: failed to launch chromium (${e.message}). Install it: npx playwright install chromium`);
  process.exit(2);
}

try {
  const page = await browser.newPage();
  const jsErrors = [];
  page.on('pageerror', (err) => jsErrors.push(String(err)));
  page.on('console', (msg) => { if (msg.type() === 'error') jsErrors.push(msg.text()); });

  await page.goto(pathToFileURL(entryPath).href, { waitUntil: 'load' });
  // Give scripts a tick to initialise (rAF games, deferred DOM).
  await page.waitForTimeout(300);

  if (smoke.no_js_errors && jsErrors.length > 0) {
    failures.push(`JS errors on load: ${jsErrors.slice(0, 3).join(' | ')}`);
  }

  for (const sel of smoke.dom_present || []) {
    if ((await page.locator(sel).count()) === 0) failures.push(`missing DOM element: ${sel}`);
  }

  for (const step of smoke.interactions || []) {
    const watch = step.expect_text_change;
    const before = watch ? await safeText(page, watch) : null;

    if (step.press) await page.keyboard.press(step.press);
    if (step.click) await page.locator(step.click).first().click({ timeout: 2000 }).catch(
      (e) => failures.push(`click ${step.click} failed: ${e.message}`));
    await page.waitForTimeout(200);

    if (watch) {
      const after = await safeText(page, watch);
      if (before === after) failures.push(`no state change in ${watch} after ${describe(step)} (stayed "${before}")`);
    }
    if (step.expect_contains) {
      const { selector, text } = step.expect_contains;
      const actual = await safeText(page, selector);
      if (!actual.includes(text)) failures.push(`${selector} does not contain "${text}" (got "${actual}")`);
    }
  }
} catch (e) {
  failures.push(`smoke run error: ${e.message}`);
} finally {
  await browser.close();
}

if (failures.length === 0) {
  console.error('browser-smoke: OK');
  process.exit(0);
}
console.error('browser-smoke: FAIL\n  - ' + failures.join('\n  - '));
process.exit(1);

function describe(step) {
  return step.press ? `press ${step.press}` : step.click ? `click ${step.click}` : 'interaction';
}
async function safeText(page, selector) {
  try { return (await page.locator(selector).first().textContent({ timeout: 1000 })) ?? ''; }
  catch { return ''; }
}
