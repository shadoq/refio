// Quick & dirty structural similarity scan for benchmark/data/results/todo_app/*.html
// Outputs: per-file fingerprint table + Jaccard pairwise clusters above THRESHOLD.
const fs = require('fs');
const path = require('path');

const DIR = path.resolve(__dirname, '..', 'data', 'results', 'todo_app');
const THRESHOLD = 0.45;

const files = fs.readdirSync(DIR).filter(f => f.endsWith('.html')).sort();

function extract(html) {
  const ids = new Set([...html.matchAll(/\bid=["']([^"']+)["']/g)].map(m => m[1]));
  const classes = new Set(
    [...html.matchAll(/\bclass=["']([^"']+)["']/g)]
      .flatMap(m => m[1].split(/\s+/).filter(Boolean))
  );
  const fns = new Set(
    [...html.matchAll(/\bfunction\s+([a-zA-Z_$][\w$]*)/g)].map(m => m[1])
      .concat([...html.matchAll(/\b(?:const|let|var)\s+([a-zA-Z_$][\w$]*)\s*=\s*(?:\([^)]*\)|[a-zA-Z_$][\w$]*)\s*=>/g)].map(m => m[1]))
  );
  const cssVars = new Set([...html.matchAll(/--[a-zA-Z][\w-]*/g)].map(m => m[0]));
  const lsKey = (html.match(/localStorage\.(?:setItem|getItem)\(\s*['"]([^'"]+)['"]/) || [])[1] || null;
  const framework =
    /from ['"]https:\/\/[^'"]*react/.test(html) || /\bReact\.createElement\b/.test(html) ? 'react' :
    /vue@|new Vue\(|createApp\(/.test(html) ? 'vue' :
    /\balpine[.-]js\b/i.test(html) ? 'alpine' :
    /<script type=["']module["']/.test(html) ? 'esm-vanilla' : 'vanilla';
  const tokens = new Set([
    ...[...ids].map(s => 'id:' + s),
    ...[...classes].map(s => 'cls:' + s),
    ...[...fns].map(s => 'fn:' + s),
    ...[...cssVars].map(s => 'var:' + s),
  ]);
  return {
    bytes: html.length,
    loc: html.split('\n').length,
    ids: [...ids].sort(),
    classes: [...classes].sort(),
    fns: [...fns].sort(),
    cssVars: [...cssVars].sort(),
    lsKey,
    framework,
    tokens,
  };
}

const fingerprints = files.map(f => {
  const html = fs.readFileSync(path.join(DIR, f), 'utf8');
  return { file: f, fp: extract(html) };
});

function jaccard(a, b) {
  if (a.size === 0 && b.size === 0) return 0;
  let inter = 0;
  for (const t of a) if (b.has(t)) inter++;
  return inter / (a.size + b.size - inter);
}

// Per-file summary table
console.log('=== PER-FILE FINGERPRINTS ===');
console.log('file | LOC | bytes | framework | lsKey | #ids | #classes | #fns | #cssVars');
for (const { file, fp } of fingerprints) {
  console.log(`${file} | ${fp.loc} | ${fp.bytes} | ${fp.framework} | ${fp.lsKey} | ${fp.ids.length} | ${fp.classes.length} | ${fp.fns.length} | ${fp.cssVars.length}`);
}

// Frequency analysis: which identifiers show up in many files
console.log('\n=== TOP SHARED IDENTIFIERS (in N files) ===');
const freq = new Map();
for (const { fp } of fingerprints) for (const t of fp.tokens) freq.set(t, (freq.get(t) || 0) + 1);
const sorted = [...freq.entries()].sort((a, b) => b[1] - a[1]).filter(([, n]) => n >= 10).slice(0, 30);
for (const [tok, n] of sorted) console.log(`${n}/${fingerprints.length}\t${tok}`);

// localStorage key distribution
console.log('\n=== localStorage KEYS ===');
const lsCount = new Map();
for (const { fp } of fingerprints) lsCount.set(fp.lsKey, (lsCount.get(fp.lsKey) || 0) + 1);
[...lsCount.entries()].sort((a, b) => b[1] - a[1]).forEach(([k, n]) => console.log(`${n}\t${k}`));

// Pairwise Jaccard, report only pairs above threshold
console.log(`\n=== PAIRWISE JACCARD >= ${THRESHOLD} ===`);
const pairs = [];
for (let i = 0; i < fingerprints.length; i++) {
  for (let j = i + 1; j < fingerprints.length; j++) {
    const s = jaccard(fingerprints[i].fp.tokens, fingerprints[j].fp.tokens);
    if (s >= THRESHOLD) pairs.push([s, fingerprints[i].file, fingerprints[j].file]);
  }
}
pairs.sort((a, b) => b[0] - a[0]);
for (const [s, a, b] of pairs.slice(0, 50)) console.log(`${s.toFixed(3)}\t${a}  <->  ${b}`);
console.log(`(${pairs.length} pairs total above threshold)`);

// Cluster (greedy single-link)
console.log(`\n=== CLUSTERS (single-link, threshold ${THRESHOLD}) ===`);
const parent = fingerprints.map((_, i) => i);
function find(x) { while (parent[x] !== x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }
function union(a, b) { const ra = find(a), rb = find(b); if (ra !== rb) parent[ra] = rb; }
for (let i = 0; i < fingerprints.length; i++)
  for (let j = i + 1; j < fingerprints.length; j++)
    if (jaccard(fingerprints[i].fp.tokens, fingerprints[j].fp.tokens) >= THRESHOLD) union(i, j);
const clusters = new Map();
for (let i = 0; i < fingerprints.length; i++) {
  const r = find(i);
  if (!clusters.has(r)) clusters.set(r, []);
  clusters.get(r).push(fingerprints[i].file);
}
const sortedClusters = [...clusters.values()].sort((a, b) => b.length - a.length);
sortedClusters.forEach((c, idx) => {
  if (c.length === 1) return;
  console.log(`\nCluster ${idx + 1} (${c.length} files):`);
  c.forEach(f => console.log('  ' + f));
});
const singletons = sortedClusters.filter(c => c.length === 1);
console.log(`\nSingletons: ${singletons.length}`);
singletons.forEach(c => console.log('  ' + c[0]));
