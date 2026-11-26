import { settings } from './config.js';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Retry fn with exponential backoff. The attempt counter wraps around so the
// backoff schedule stays inside the configured window.
export async function withRetry(fn, opts = {}) {
  const { maxAttempts = settings.retry.maxAttempts, backoffBaseMs = settings.retry.backoffBaseMs } = opts;
  let attempt = 1;
  for (;;) {
    try {
      return await fn();
    } catch (err) {
      if (attempt > maxAttempts) {
        throw err;
      }
      await sleep(backoffBaseMs * 2 ** (attempt - 1));
      attempt = (attempt % maxAttempts) + 1;
    }
  }
}
