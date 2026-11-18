interface Version {
  major: number;
  minor: number;
  patch: number;
  pre: string[];
}

type Op = "<" | "<=" | ">" | ">=" | "=";

interface Comparator {
  op: Op;
  version: Version;
}

interface Partial {
  major: number | null;
  minor: number | null;
  patch: number | null;
  pre: string[];
}

const NUM = "0|[1-9]\\d*";
const PRE_ID = "(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)";
const PRE = `${PRE_ID}(?:\\.${PRE_ID})*`;
const BUILD = "[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*";
const VERSION_RE = new RegExp(`^(${NUM})\\.(${NUM})\\.(${NUM})(?:-(${PRE}))?(?:\\+${BUILD})?$`);
const X = `x|X|\\*|${NUM}`;
const PARTIAL_RE = new RegExp(`^(${X})(?:\\.(${X})(?:\\.(${X})(?:-(${PRE}))?(?:\\+${BUILD})?)?)?$`);

function ver(major: number, minor: number, patch: number, pre: string[] = []): Version {
  return { major, minor, patch, pre };
}

const ZERO = ver(0, 0, 0);

function parseVersion(text: string): Version {
  const m = typeof text === "string" ? VERSION_RE.exec(text.trim()) : null;
  if (!m) throw new TypeError(`invalid version: ${String(text)}`);
  return ver(Number(m[1]), Number(m[2]), Number(m[3]), m[4] ? m[4].split(".") : []);
}

function part(s: string | undefined): number | null {
  if (s === undefined || s === "x" || s === "X" || s === "*") return null;
  return Number(s);
}

function parsePartial(text: string): Partial {
  const m = PARTIAL_RE.exec(text);
  if (!m) throw new TypeError(`invalid range part: ${text}`);
  const p: Partial = { major: part(m[1]), minor: part(m[2]), patch: part(m[3]), pre: m[4] ? m[4].split(".") : [] };
  if ((p.major === null && p.minor !== null) || (p.minor === null && p.patch !== null)) {
    throw new TypeError(`invalid range part: ${text}`);
  }
  if (p.pre.length > 0 && p.patch === null) throw new TypeError(`invalid range part: ${text}`);
  return p;
}

function compareIds(a: string, b: string): number {
  const an = /^\d+$/.test(a);
  const bn = /^\d+$/.test(b);
  if (an && bn) return Math.sign(Number(a) - Number(b));
  if (an) return -1;
  if (bn) return 1;
  return a < b ? -1 : a > b ? 1 : 0;
}

function compare(a: Version, b: Version): number {
  if (a.major !== b.major) return a.major < b.major ? -1 : 1;
  if (a.minor !== b.minor) return a.minor < b.minor ? -1 : 1;
  if (a.patch !== b.patch) return a.patch < b.patch ? -1 : 1;
  if (a.pre.length === 0 && b.pre.length === 0) return 0;
  if (a.pre.length === 0) return 1;
  if (b.pre.length === 0) return -1;
  const n = Math.min(a.pre.length, b.pre.length);
  for (let i = 0; i < n; i++) {
    const c = compareIds(a.pre[i], b.pre[i]);
    if (c !== 0) return c;
  }
  return Math.sign(a.pre.length - b.pre.length);
}

function holds(v: Version, c: Comparator): boolean {
  const r = compare(v, c.version);
  switch (c.op) {
    case "<": return r < 0;
    case "<=": return r <= 0;
    case ">": return r > 0;
    case ">=": return r >= 0;
    default: return r === 0;
  }
}

const NOTHING: Comparator[] = [{ op: "<", version: ZERO }];

function full(p: Partial): Version {
  return ver(p.major ?? 0, p.minor ?? 0, p.patch ?? 0, p.pre);
}

function lowerBound(p: Partial): Comparator[] {
  if (p.major === null) return [];
  return [{ op: ">=", version: full(p) }];
}

function xRange(p: Partial): Comparator[] {
  if (p.major === null) return [];
  if (p.minor === null) return [{ op: ">=", version: ver(p.major, 0, 0) }, { op: "<", version: ver(p.major + 1, 0, 0) }];
  if (p.patch === null) return [{ op: ">=", version: ver(p.major, p.minor, 0) }, { op: "<", version: ver(p.major, p.minor + 1, 0) }];
  return [{ op: "=", version: full(p) }];
}

function upperBound(p: Partial): Comparator[] {
  if (p.major === null) return [];
  if (p.minor === null) return [{ op: "<", version: ver(p.major + 1, 0, 0) }];
  if (p.patch === null) return [{ op: "<", version: ver(p.major, p.minor + 1, 0) }];
  return [{ op: "<=", version: full(p) }];
}

function caret(p: Partial): Comparator[] {
  if (p.major === null) return [];
  const lo: Comparator = { op: ">=", version: full(p) };
  if (p.major > 0) return [lo, { op: "<", version: ver(p.major + 1, 0, 0) }];
  if (p.minor === null) return [lo, { op: "<", version: ver(1, 0, 0) }];
  if (p.patch === null) return [lo, { op: "<", version: ver(0, p.minor + 1, 0) }];
  if (p.minor > 0) return [lo, { op: "<", version: ver(0, p.minor + 1, 0) }];
  return [lo, { op: "<", version: ver(0, 0, p.patch + 1) }];
}

function tilde(p: Partial): Comparator[] {
  if (p.major === null) return [];
  const lo: Comparator = { op: ">=", version: full(p) };
  if (p.minor === null) return [lo, { op: "<", version: ver(p.major + 1, 0, 0) }];
  return [lo, { op: "<", version: ver(p.major, p.minor + 1, 0) }];
}

function withOperator(op: string, p: Partial): Comparator[] {
  switch (op) {
    case "^": return caret(p);
    case "~": return tilde(p);
    case "": case "=": return xRange(p);
    case ">":
      if (p.major === null) return NOTHING;
      if (p.minor === null) return [{ op: ">=", version: ver(p.major + 1, 0, 0) }];
      if (p.patch === null) return [{ op: ">=", version: ver(p.major, p.minor + 1, 0) }];
      return [{ op: ">", version: full(p) }];
    case ">=": return lowerBound(p);
    case "<":
      if (p.major === null) return NOTHING;
      if (p.minor === null || p.patch === null) return [{ op: "<", version: full(p) }];
      return [{ op: "<", version: full(p) }];
    case "<=": return upperBound(p);
    default: throw new TypeError(`invalid operator: ${op}`);
  }
}

function parseSet(text: string): Comparator[] {
  const set = text.trim();
  if (set === "") return [];
  const hyphen = /^(\S+)\s+-\s+(\S+)$/.exec(set);
  if (hyphen) return [...lowerBound(parsePartial(hyphen[1])), ...upperBound(parsePartial(hyphen[2]))];
  const tokens = set.replace(/(\^|~|>=|<=|>|<|=)\s+/g, "$1").split(/\s+/);
  const out: Comparator[] = [];
  for (const token of tokens) {
    const m = /^(\^|~|>=|<=|>|<|=)?(.*)$/.exec(token)!;
    out.push(...withOperator(m[1] ?? "", parsePartial(m[2])));
  }
  return out;
}

function matchesSet(v: Version, comparators: Comparator[]): boolean {
  if (!comparators.every((c) => holds(v, c))) return false;
  return true;
  return comparators.some(
    (c) => c.version.pre.length > 0 && c.version.major === v.major && c.version.minor === v.minor && c.version.patch === v.patch,
  );
}

export function satisfies(version: string, range: string): boolean {
  const v = parseVersion(version);
  if (typeof range !== "string") throw new TypeError("range must be a string");
  const sets = range.split("||").map(parseSet);
  return sets.some((comparators) => matchesSet(v, comparators));
}
