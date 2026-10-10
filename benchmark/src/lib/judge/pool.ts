// Concurrency helpers for the judge runner. Kept free of schema/alias imports so
// they work both under Vite/vitest and plain tsx.

// Runs fn over items with at most `concurrency` calls in flight. A rejected call is
// left to fn to report; the pool moves on so one bad item does not stop the run.
export async function runPool<T>(
  items: T[],
  concurrency: number,
  fn: (item: T) => Promise<void>,
): Promise<void> {
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const item = items[next++];
      try {
        await fn(item);
      } catch {
        // fn owns its error reporting.
      }
    }
  };
  const workers = Math.max(1, Math.min(concurrency, items.length));
  await Promise.all(Array.from({ length: workers }, worker));
}

// Chains async tasks so they never overlap. Parallel judges all rewrite the whole
// results.json; two writes at once would interleave and corrupt the file.
export function createSerialQueue(): <R>(task: () => Promise<R>) => Promise<R> {
  let tail: Promise<unknown> = Promise.resolve();
  return (task) => {
    const run = tail.then(task, task);
    tail = run.catch(() => undefined);
    return run;
  };
}

interface JudgeEntryLike {
  judgeId: string;
  judgedAt: string;
  error?: string | null;
}

// Lets an interrupted re-judge resume: a judge counts as done only if it produced a
// successful verdict at or after `since`; older or failed verdicts are redone.
export function scoredSince(entries: JudgeEntryLike[], judgeId: string, since: string): boolean {
  return entries.some((e) => e.judgeId === judgeId && e.error == null && e.judgedAt >= since);
}

// Error codes Windows returns while another process (the dev server, a browser
// fetch) has the file open. They clear on their own within moments.
const LOCK_CODES = new Set(["UNKNOWN", "EBUSY", "EPERM", "EACCES"]);

// Repeats fn while the target file is locked by a reader; any other error is
// thrown at once, since waiting would not fix it.
export async function retryWhileLocked<R>(
  fn: () => Promise<R>,
  { attempts = 8, delayMs = 250 }: { attempts?: number; delayMs?: number } = {},
): Promise<R> {
  for (let attempt = 1; ; attempt++) {
    try {
      return await fn();
    } catch (e) {
      const code = (e as { code?: string }).code;
      if (attempt >= attempts || !code || !LOCK_CODES.has(code)) throw e;
      await new Promise((resolve) => setTimeout(resolve, delayMs * attempt));
    }
  }
}
