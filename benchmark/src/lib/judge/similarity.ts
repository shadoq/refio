// Deterministic code-similarity metric for cross-attempt stability. Token Jaccard
// after whitespace normalization: coarse but stable and dependency-free. Shared
// by the stability runner and covered by vitest.

export function tokenize(text: string): string[] {
  return text.toLowerCase().split(/\s+/).filter(Boolean);
}

export function jaccard(a: Set<string>, b: Set<string>): number {
  if (a.size === 0 && b.size === 0) return 1;
  let intersection = 0;
  for (const t of a) if (b.has(t)) intersection++;
  const union = a.size + b.size - intersection;
  return union === 0 ? 1 : intersection / union;
}

export function codeSimilarity(a: string, b: string): number {
  return jaccard(new Set(tokenize(a)), new Set(tokenize(b)));
}

// Mean similarity over all unordered pairs. 1 for a single text (nothing varies).
export function averagePairwiseSimilarity(texts: string[]): number {
  if (texts.length < 2) return 1;
  const sets = texts.map((t) => new Set(tokenize(t)));
  let sum = 0;
  let pairs = 0;
  for (let i = 0; i < sets.length; i++) {
    for (let j = i + 1; j < sets.length; j++) {
      sum += jaccard(sets[i], sets[j]);
      pairs++;
    }
  }
  return pairs === 0 ? 1 : sum / pairs;
}
