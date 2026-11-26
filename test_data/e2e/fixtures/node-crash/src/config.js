// Shared runtime settings for the job worker.
export const settings = {
  refreshIntervalMs: 300000,
  retry: { maxAttempts: 3, backoffBaseMs: 10 },
};
