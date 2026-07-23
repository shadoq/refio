// Extract the first parsable JSON object from a strong-judge CLI's raw output,
// which usually wraps the verdict in prose and/or markdown code fences.

// Find the substring of a balanced { ... } object starting at `start`, honoring
// string literals and escapes so braces inside strings do not confuse the count.
function balancedObject(text: string, start: number): string | null {
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = start; i < text.length; i++) {
    const ch = text[i];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (ch === "\\") {
        escaped = true;
      } else if (ch === '"') {
        inString = false;
      }
      continue;
    }
    if (ch === '"') {
      inString = true;
    } else if (ch === "{") {
      depth++;
    } else if (ch === "}") {
      depth--;
      if (depth === 0) return text.slice(start, i + 1);
    }
  }
  return null;
}

// Returns the first JSON object that parses and satisfies `accept`, or null.
// Scans every `{`, so a leading non-JSON brace or a non-verdict object (e.g. the
// judge emits a small status object before the real verdict) does not block a
// valid object further along. `accept` defaults to any parsed object.
export function extractJson(
  text: string,
  accept: (value: unknown) => boolean = () => true,
): unknown | null {
  for (let i = 0; i < text.length; i++) {
    if (text[i] !== "{") continue;
    const candidate = balancedObject(text, i);
    if (!candidate) break;
    let parsed: unknown;
    try {
      parsed = JSON.parse(candidate);
    } catch {
      continue; // Not valid JSON starting here; keep scanning for the next `{`.
    }
    if (accept(parsed)) return parsed;
  }
  return null;
}
