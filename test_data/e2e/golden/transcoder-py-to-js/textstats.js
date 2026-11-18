"use strict";

const PUNCTUATION = ".,;:!?\"'()";

function stripPunctuation(word) {
  let start = 0;
  let end = word.length;
  while (start < end && PUNCTUATION.includes(word[start])) start++;
  while (end > start && PUNCTUATION.includes(word[end - 1])) end--;
  return word.slice(start, end);
}

function words(text) {
  return text.split(/\s+/).filter((w) => w.length > 0);
}

function word_freq(text, top) {
  const counts = new Map();
  for (const raw of words(text.toLowerCase())) {
    const word = stripPunctuation(raw);
    if (word) counts.set(word, (counts.get(word) || 0) + 1);
  }
  let items = [...counts.entries()].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
  if (top !== undefined && top !== null) items = items.slice(0, top);
  return items.map(([word, n]) => [word, n]);
}

function median(nums) {
  if (nums.length === 0) throw new Error("median of empty list");
  const ordered = [...nums].sort((a, b) => a - b);
  const mid = Math.floor(ordered.length / 2);
  if (ordered.length % 2) return ordered[mid];
  return (ordered[mid - 1] + ordered[mid]) / 2;
}

function round_half_even(x) {
  const floor = Math.floor(x);
  const diff = x - floor;
  let result;
  if (diff < 0.5) result = floor;
  else if (diff > 0.5) result = floor + 1;
  else result = floor % 2 === 0 ? floor : floor + 1;
  return result + 0;
}

function wrap(text, width) {
  const lines = [];
  let current = "";
  for (const word of words(text)) {
    if (!current) current = word;
    else if (current.length + 1 + word.length <= width) current += " " + word;
    else {
      lines.push(current);
      current = word;
    }
  }
  if (current) lines.push(current);
  return lines;
}

function chunk(seq, size) {
  if (size <= 0) throw new Error("size must be positive");
  const out = [];
  for (let i = 0; i < seq.length; i += size) out.push(seq.slice(i, i + size));
  return out;
}

function title_case(text) {
  let out = "";
  let prevIsLetter = false;
  for (const ch of text) {
    const isLetter = /[A-Za-z]/.test(ch);
    out += isLetter ? (prevIsLetter ? ch.toLowerCase() : ch.toUpperCase()) : ch;
    prevIsLetter = isLetter;
  }
  return out;
}

function ring_index(i, n) {
  return ((i % n) + n) % n;
}

function half_of(x) {
  return Math.floor(x / 2);
}

module.exports = { word_freq, median, round_half_even, wrap, chunk, title_case, ring_index, half_of };
