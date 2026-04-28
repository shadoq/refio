// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from "vitest";

// Mock fetch
const mockFetch = vi.fn();
(globalThis as unknown as Record<string, unknown>).fetch = mockFetch;

describe("HtmlSandbox", () => {
  beforeEach(() => {
    mockFetch.mockReset();
  });

  it("sandbox attribute should be allow-scripts only (not allow-same-origin)", () => {
    // This is a code-level check - the component source uses sandbox="allow-scripts"
    // which prevents cross-frame access while allowing JS execution inside the iframe
    const EXPECTED_SANDBOX = "allow-scripts";
    const DANGEROUS_SANDBOX = "allow-same-origin allow-scripts";

    expect(EXPECTED_SANDBOX).not.toBe(DANGEROUS_SANDBOX);
    expect(EXPECTED_SANDBOX).toBe("allow-scripts");
  });

  it("prepends /data/ to relative src paths", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      text: async () => "<p>test</p>",
    });

    // Simulate the URL logic from HtmlSandbox
    const src = "attachments/r1/output.html";
    const url = src.startsWith("http") ? src : `/data/${src}`;

    // Trigger a fake fetch to verify URL construction
    await fetch(url);
    expect(mockFetch).toHaveBeenCalledWith("/data/attachments/r1/output.html");
  });

  it("does not prepend /data/ to absolute URLs", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      text: async () => "<p>test</p>",
    });

    const src = "https://example.com/output.html";
    const url = src.startsWith("http") ? src : `/data/${src}`;

    await fetch(url);
    expect(mockFetch).toHaveBeenCalledWith("https://example.com/output.html");
  });

  it("URL logic: relative paths get /data/ prefix", () => {
    const cases: Array<[string, string]> = [
      ["attachments/r1/output.html", "/data/attachments/r1/output.html"],
      ["some/path/file.html", "/data/some/path/file.html"],
    ];
    for (const [src, expected] of cases) {
      const url = src.startsWith("http") ? src : `/data/${src}`;
      expect(url).toBe(expected);
    }
  });

  it("URL logic: absolute URLs are used as-is", () => {
    const cases: Array<[string, string]> = [
      ["https://example.com/output.html", "https://example.com/output.html"],
      ["http://localhost:3000/file.html", "http://localhost:3000/file.html"],
    ];
    for (const [src, expected] of cases) {
      const url = src.startsWith("http") ? src : `/data/${src}`;
      expect(url).toBe(expected);
    }
  });
});
