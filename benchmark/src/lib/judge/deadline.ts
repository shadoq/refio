// A hard deadline around a promise that may never settle. The judge renderer runs
// untrusted artifacts in a headless browser; a runaway requestAnimationFrame can
// starve the event loop so even Playwright's per-operation timeouts never fire and
// a single page hangs the whole queue. Racing the work against a timer lets the
// caller abandon it and move on. The abandoned work keeps running, so the caller is
// responsible for tearing down its resources (closing the browser) afterwards.

export interface Deadline<T> {
  // Resolves with the work's value if it settles first, or with `onTimeout()` if
  // the deadline is hit first. Never rejects on timeout.
  result: T;
  timedOut: boolean;
}

export async function withDeadline<T>(
  work: Promise<T>,
  ms: number,
  onTimeout: () => T,
): Promise<Deadline<T>> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<{ timedOut: true }>((resolve) => {
    timer = setTimeout(() => resolve({ timedOut: true }), ms);
  });
  try {
    const winner = await Promise.race([
      work.then((value) => ({ timedOut: false as const, value })),
      deadline,
    ]);
    if (winner.timedOut) return { result: onTimeout(), timedOut: true };
    return { result: winner.value, timedOut: false };
  } finally {
    if (timer) clearTimeout(timer);
  }
}
