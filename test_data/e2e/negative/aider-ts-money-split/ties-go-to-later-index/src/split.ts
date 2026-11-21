function isSafePositive(n: number): boolean {
  return Number.isSafeInteger(n) && n > 0;
}

export function split(amount: number, ratios: number[]): number[] {
  if (!Number.isSafeInteger(amount)) {
    throw new RangeError(`amount must be a safe integer: ${amount}`);
  }
  if (ratios.length === 0 || !ratios.every(isSafePositive)) {
    throw new RangeError("ratios must be a non-empty array of positive safe integers");
  }
  const negative = amount < 0;
  const abs = BigInt(negative ? -amount : amount);
  const weights = ratios.map((r) => BigInt(r));
  const total = weights.reduce((a, b) => a + b, 0n);

  const shares = weights.map((w) => (abs * w) / total);
  const remainders = weights.map((w) => (abs * w) % total);
  let left = Number(abs - shares.reduce((a, b) => a + b, 0n));

  const order = weights.map((_, i) => i);
  order.sort((i, j) => {
    if (remainders[i] !== remainders[j]) return remainders[i] > remainders[j] ? -1 : 1;
    return j - i;
  });
  for (const i of order) {
    if (left === 0) break;
    shares[i] += 1n;
    left--;
  }
  return shares.map((s) => {
    const n = Number(s);
    return negative && n !== 0 ? -n : n;
  });
}
