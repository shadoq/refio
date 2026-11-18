export function split(amount: number, ratios: number[]): number[] {
  if (!Number.isSafeInteger(amount)) throw new RangeError("amount");
  if (ratios.length === 0 || !ratios.every((r) => Number.isSafeInteger(r) && r > 0)) throw new RangeError("ratios");
  const sign = amount < 0 ? -1 : 1;
  const abs = Math.abs(amount);
  const total = ratios.reduce((a, b) => a + b, 0);
  const shares = ratios.map((r) => Math.floor((abs * r) / total));
  const rems = ratios.map((r, i) => (abs * r) / total - shares[i]);
  let left = abs - shares.reduce((a, b) => a + b, 0);
  const order = ratios.map((_, i) => i).sort((i, j) => rems[j] - rems[i] || i - j);
  for (const i of order) {
    if (left <= 0) break;
    shares[i]++;
    left--;
  }
  return shares.map((s) => (s === 0 ? 0 : sign * s));
}
