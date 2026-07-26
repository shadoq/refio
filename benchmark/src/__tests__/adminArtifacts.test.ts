// @vitest-environment node
import { describe, it, expect } from "vitest";
import { inboxScreenshots, dataUrl, repoPath, ideaOpenHref } from "@/lib/adminArtifacts";

describe("inboxScreenshots", () => {
  it("collects image attachments then judge screenshots, de-duplicated", () => {
    const shots = inboxScreenshots({
      attachments: [
        { type: "html", src: "attachments/x/artifact.html" },
        { type: "image", src: "attachments/x/_judge/shot-full.png" },
      ],
      judgeScores: [
        { screenshots: ["attachments/x/_judge/shot-full.png", "attachments/x/_judge/shot-1.png"] },
      ],
    });
    // html is not a screenshot; the shared shot-full appears once; image attachments first.
    expect(shots).toEqual([
      "attachments/x/_judge/shot-full.png",
      "attachments/x/_judge/shot-1.png",
    ]);
  });

  it("returns an empty list when there are no images or judge screenshots", () => {
    expect(inboxScreenshots({ attachments: [{ type: "html", src: "a.html" }] })).toEqual([]);
    expect(inboxScreenshots({})).toEqual([]);
  });
});

describe("artifact path helpers", () => {
  it("dataUrl serves relative srcs under /data and passes http URLs through", () => {
    expect(dataUrl("attachments/x/artifact.html")).toBe("/data/attachments/x/artifact.html");
    expect(dataUrl("http://host/a.html")).toBe("http://host/a.html");
  });

  it("repoPath prefixes the repo-relative data dir", () => {
    expect(repoPath("attachments/x/artifact.html")).toBe(
      "benchmark/data/attachments/x/artifact.html",
    );
  });

  it("ideaOpenHref builds an absolute idea:// link when a data root is known", () => {
    expect(ideaOpenHref("attachments/x/a.html", "D:/repo/benchmark/data")).toBe(
      "idea://open?file=" + encodeURIComponent("D:/repo/benchmark/data/attachments/x/a.html"),
    );
  });

  it("ideaOpenHref falls back to the repo-relative path without a data root", () => {
    expect(ideaOpenHref("attachments/x/a.html")).toBe(
      "idea://open?file=" + encodeURIComponent("benchmark/data/attachments/x/a.html"),
    );
  });
});
