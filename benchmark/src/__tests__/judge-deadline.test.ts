// @vitest-environment node
import { describe, it, expect, vi, afterEach } from "vitest";
import { withDeadline } from "@/lib/judge/deadline";

afterEach(() => {
  vi.useRealTimers();
});

describe("withDeadline", () => {
  // Work that settles before the deadline must pass its value straight through.
  it("returns the work's value when it settles in time", async () => {
    const res = await withDeadline(Promise.resolve("done"), 1000, () => "fallback");
    expect(res).toEqual({ result: "done", timedOut: false });
  });

  // The bug this guards: a frozen artifact page never settles, so without a
  // deadline the whole queue hangs. The timer must win and yield the fallback.
  it("returns the fallback when the work never settles", async () => {
    vi.useFakeTimers();
    const never = new Promise<string>(() => {});
    const pending = withDeadline(never, 5000, () => "fallback");
    await vi.advanceTimersByTimeAsync(5000);
    expect(await pending).toEqual({ result: "fallback", timedOut: true });
  });

  // A rejection is a settled outcome, not a hang, so it must propagate rather than
  // be masked as a timeout - a broken render should surface its real error.
  it("propagates a rejection instead of masking it as a timeout", async () => {
    await expect(
      withDeadline(Promise.reject(new Error("boom")), 1000, () => "fallback"),
    ).rejects.toThrow("boom");
  });
});
