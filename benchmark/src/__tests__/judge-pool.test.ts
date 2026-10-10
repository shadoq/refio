// @vitest-environment node
import { describe, it, expect } from "vitest";
import { runPool, createSerialQueue, scoredSince, retryWhileLocked } from "@/lib/judge/pool";

const tick = () => new Promise((resolve) => setTimeout(resolve, 1));

describe("runPool", () => {
  it("never runs more judgements at once than the concurrency allows", async () => {
    let running = 0;
    let peak = 0;
    const done: number[] = [];
    await runPool([1, 2, 3, 4, 5, 6, 7, 8, 9], 4, async (n) => {
      running++;
      peak = Math.max(peak, running);
      await tick();
      done.push(n);
      running--;
    });
    expect(peak).toBe(4);
    expect(done.sort((a, b) => a - b)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9]);
  });

  it("keeps going after one item fails, so one bad result does not stop the run", async () => {
    const done: number[] = [];
    await runPool([1, 2, 3], 2, async (n) => {
      if (n === 1) throw new Error("boom");
      done.push(n);
    });
    expect(done.sort()).toEqual([2, 3]);
  });
});

describe("createSerialQueue", () => {
  it("runs results.json saves one after another even when judges finish together", async () => {
    const enqueue = createSerialQueue();
    let writing = false;
    let overlapped = false;
    const save = () =>
      enqueue(async () => {
        if (writing) overlapped = true;
        writing = true;
        await tick();
        writing = false;
      });
    await Promise.all([save(), save(), save()]);
    expect(overlapped).toBe(false);
  });

  it("does not block later saves after one save fails", async () => {
    const enqueue = createSerialQueue();
    await expect(enqueue(async () => Promise.reject(new Error("disk")))).rejects.toThrow("disk");
    await expect(enqueue(async () => "ok")).resolves.toBe("ok");
  });
});

describe("scoredSince", () => {
  const entries = [
    { judgeId: "codex", judgedAt: "2026-10-10T09:30:00.000Z", error: null },
    { judgeId: "claude-code", judgedAt: "2026-10-03T12:00:00.000Z", error: null },
    { judgeId: "gemini", judgedAt: "2026-10-10T09:40:00.000Z", error: "timeout" },
  ];
  const since = "2026-10-10T09:13:00.000Z";

  it("counts a verdict made after the resume point as done", () => {
    expect(scoredSince(entries, "codex", since)).toBe(true);
  });

  it("re-judges a verdict from before the resume point", () => {
    expect(scoredSince(entries, "claude-code", since)).toBe(false);
  });

  it("re-judges a recent failed verdict", () => {
    expect(scoredSince(entries, "gemini", since)).toBe(false);
  });
});

describe("retryWhileLocked", () => {
  const lockError = () => Object.assign(new Error("unknown error, open results.json"), { code: "UNKNOWN" });

  it("retries a save while Windows holds results.json open for a reader", async () => {
    let calls = 0;
    const result = await retryWhileLocked(
      async () => {
        calls++;
        if (calls < 3) throw lockError();
        return "saved";
      },
      { attempts: 5, delayMs: 1 },
    );
    expect(result).toBe("saved");
    expect(calls).toBe(3);
  });

  it("gives up after the last attempt and reports the lock", async () => {
    let calls = 0;
    await expect(
      retryWhileLocked(
        async () => {
          calls++;
          throw lockError();
        },
        { attempts: 3, delayMs: 1 },
      ),
    ).rejects.toThrow("unknown error");
    expect(calls).toBe(3);
  });

  it("does not retry an error that waiting cannot fix", async () => {
    let calls = 0;
    await expect(
      retryWhileLocked(
        async () => {
          calls++;
          throw Object.assign(new Error("invalid results file"), { code: undefined });
        },
        { attempts: 5, delayMs: 1 },
      ),
    ).rejects.toThrow("invalid results file");
    expect(calls).toBe(1);
  });
});
