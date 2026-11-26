import { refreshAll } from './refresh.js';
import { settings } from './config.js';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Background scheduler: kicks off a refresh round every intervalMs. The round
// is intentionally not awaited so a slow catalog cannot stall the timer.
export function startWorker(deps) {
  const intervalMs = deps.intervalMs ?? settings.refreshIntervalMs;
  let running = true;
  const tick = async () => {
    while (running) {
      refreshAll(deps.listItems(), deps);
      await sleep(intervalMs);
    }
  };
  tick();
  return { stop() { running = false; } };
}
