// Helpers for the admin review UI (Queue): which screenshots to show for an
// imported run, and how to reach its artifact file (dev URL, repo-relative path,
// IntelliJ deep link). Pure and structurally typed so they stay unit-testable and
// decoupled from the full results schema.

// Distinct screenshot srcs for a review artifact: the image attachments plus any
// screenshots the deterministic/strong judges captured, de-duplicated, image
// attachments first (they are the canonical "what it rendered to" shots).
export function inboxScreenshots(entry: {
  attachments?: Array<{ type: string; src: string }>;
  judgeScores?: Array<{ screenshots?: string[] }>;
}): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  const add = (src: string) => {
    if (src && !seen.has(src)) {
      seen.add(src);
      out.push(src);
    }
  };
  for (const att of entry.attachments ?? []) {
    if (att.type === "image") add(att.src);
  }
  for (const set of entry.judgeScores ?? []) {
    for (const shot of set.screenshots ?? []) add(shot);
  }
  return out;
}

// The dev-server URL that serves an attachment (relative srcs live under /data).
export function dataUrl(src: string): string {
  return src.startsWith("http") ? src : `/data/${src}`;
}

// The path of the attachment relative to the repository root, for pasting into an
// editor or terminal (the viewer runs from the benchmark/ package).
export function repoPath(src: string): string {
  return `benchmark/data/${src}`;
}

// idea://open deep link to open the file in IntelliJ. `dataRoot` is the absolute
// path of benchmark/data on the dev machine (injected via Vite); without it, fall
// back to the repo-relative path.
export function ideaOpenHref(src: string, dataRoot?: string): string {
  const file = dataRoot ? `${dataRoot.replace(/[\\/]$/, "")}/${src}` : repoPath(src);
  return `idea://open?file=${encodeURIComponent(file)}`;
}
