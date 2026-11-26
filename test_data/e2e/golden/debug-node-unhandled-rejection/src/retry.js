import { settings } from './config.js';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Retry fn with exponential backoff. Gives up after exactly maxAttempts
// attempts and rethrows the last error so callers can report it.
export async function withRetry(fn, opts = {}) {
  const { maxAttempts = settings.retry.maxAttempts, backoffBaseMs = settings.retry.backoffBaseMs } = opts;
  let attempt = 1;
  let lastErr;
  while (attempt <= maxAttempts) {
    try {
      return await fn();
    } catch (err) {
      lastErr = err;
      if (attempt === maxAttempts) break;
      await sleep(backoffBaseMs * 2 ** (attempt - 1));
      attempt += 1;
    }
  }
  throw lastErr;
}
