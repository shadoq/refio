function formatRanges(numbers) {
  if (!Array.isArray(numbers)) {
    throw new TypeError('numbers must be an array');
  }
  for (const n of numbers) {
    if (!Number.isInteger(n)) {
      throw new TypeError(`not an integer: ${String(n)}`);
    }
  }
  const sorted = numbers.sort((a, b) => a - b).filter((n, k, arr) => k === 0 || arr[k - 1] !== n);
  const parts = [];
  let i = 0;
  while (i < sorted.length) {
    let j = i;
    while (j + 1 < sorted.length && sorted[j + 1] === sorted[j] + 1) {
      j++;
    }
    if (j - i >= 2) {
      parts.push(`${sorted[i]}..${sorted[j]}`);
    } else {
      for (let k = i; k <= j; k++) {
        parts.push(String(sorted[k]));
      }
    }
    i = j + 1;
  }
  return parts.join(',');
}

module.exports = { formatRanges };
