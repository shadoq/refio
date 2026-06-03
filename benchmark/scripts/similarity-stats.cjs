// Detailed similarity stats for an article: cluster composition, cross-family / cross-firm
// counts, identical pairs, top wspólne identyfikatory. Reuses extraction from similarity-viz.cjs.
const fs = require('fs');
const path = require('path');

const REPO = path.resolve(__dirname, '..', '..');
const TASK = process.argv[2] || 'todo_app';
const DIR = path.join(REPO, 'benchmark', 'data', 'results', TASK);
const THRESHOLD = 0.45;

function familyOf(file) {
  if (file.includes('anthropic_claude-haiku')) return 'Anthropic Haiku 4.5';
  if (file.includes('anthropic_claude-sonnet')) return 'Anthropic Sonnet 4.6';
  if (file.includes('ollama_qwen3.5_122b')) return 'Qwen 3.5 122B';
  if (file.includes('ollama_qwen3.5_35b')) return 'Qwen 3.5 35B';
  if (file.includes('ollama_qwen3.5_27b')) return 'Qwen 3.5 27B';
  if (file.includes('ollama_qwen3.5_9b')) return 'Qwen 3.5 9B';
  if (file.includes('ollama_qwen3.6_35b')) return 'Qwen 3.6 35B';
  if (file.includes('ollama_qwen3.6_27b')) return 'Qwen 3.6 27B';
  if (file.includes('ollama_gpt-oss_120b')) return 'GPT-OSS 120B';
  if (file.includes('ollama_gpt-oss_20b')) return 'GPT-OSS 20B';
  if (file.includes('ollama_gemma4_31b')) return 'Gemma 4 31B';
  if (file.includes('ollama_gemma4_26b')) return 'Gemma 4 26B';
  if (file.includes('openai_gpt-4.1-mini')) return 'OpenAI GPT-4.1-mini';
  if (file.includes('openai_gpt-5.1-codex-mini')) return 'OpenAI Codex-mini';
  if (file.includes('openai_gpt-5.4-mini')) return 'OpenAI GPT-5.4-mini';
  if (file.includes('zai_glm-5-turbo')) return 'Z.AI GLM-5-turbo';
  return 'other';
}
function firmOf(family) {
  if (family.startsWith('Anthropic')) return 'Anthropic';
  if (family.startsWith('OpenAI')) return 'OpenAI';
  if (family.startsWith('Z.AI')) return 'Z.AI';
  if (family.startsWith('Qwen')) return 'Qwen (Alibaba)';
  if (family.startsWith('Gemma')) return 'Gemma (Google)';
  if (family.startsWith('GPT-OSS')) return 'GPT-OSS';
  return 'other';
}
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
  return new Set([
    ...[...ids].map(s => 'id:' + s),
    ...[...classes].map(s => 'cls:' + s),
    ...[...fns].map(s => 'fn:' + s),
    ...[...cssVars].map(s => 'var:' + s),
  ]);
}

const files = fs.readdirSync(DIR).filter(f => f.endsWith('.html')).sort();
const fps = files.map(f => ({
  file: f,
  family: familyOf(f),
  firm: firmOf(familyOf(f)),
  tokens: extract(fs.readFileSync(path.join(DIR, f), 'utf8')),
}));

const N = fps.length;
const sim = Array.from({ length: N }, () => new Float32Array(N));
for (let i = 0; i < N; i++) {
  sim[i][i] = 1;
  for (let j = i + 1; j < N; j++) {
    const a = fps[i].tokens, b = fps[j].tokens;
    let inter = 0;
    for (const t of a) if (b.has(t)) inter++;
    const s = a.size + b.size - inter === 0 ? 0 : inter / (a.size + b.size - inter);
    sim[i][j] = s; sim[j][i] = s;
  }
}

const parent = fps.map((_, i) => i);
const find = x => { while (parent[x] !== x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; };
const union = (a, b) => { const ra = find(a), rb = find(b); if (ra !== rb) parent[ra] = rb; };
for (let i = 0; i < N; i++) for (let j = i + 1; j < N; j++) if (sim[i][j] >= THRESHOLD) union(i, j);
const groups = new Map();
for (let i = 0; i < N; i++) {
  const r = find(i);
  if (!groups.has(r)) groups.set(r, []);
  groups.get(r).push(i);
}
const clusters = [...groups.values()].sort((a, b) => b.length - a.length);

console.log(`\n=== ${TASK} : ${N} files ===\n`);

// Family breakdown
const famCounts = {};
fps.forEach(f => famCounts[f.family] = (famCounts[f.family] || 0) + 1);
console.log('Families:', Object.entries(famCounts).sort((a, b) => b[1] - a[1]).map(([f, n]) => `${f}=${n}`).join(', '));

// Cluster composition
console.log('\nClusters (>=2):');
clusters.forEach((g, ci) => {
  if (g.length < 2) return;
  const fams = {};
  const firms = new Set();
  g.forEach(i => {
    fams[fps[i].family] = (fams[fps[i].family] || 0) + 1;
    firms.add(fps[i].firm);
  });
  const famStr = Object.entries(fams).sort((a, b) => b[1] - a[1]).map(([f, n]) => `${f}×${n}`).join(', ');
  // Mean intra-cluster Jaccard
  let sum = 0, cnt = 0;
  for (let i = 0; i < g.length; i++) for (let j = i + 1; j < g.length; j++) { sum += sim[g[i]][g[j]]; cnt++; }
  const mean = cnt ? (sum / cnt).toFixed(3) : '1.000';
  console.log(`  C${ci + 1}: ${g.length} files | firms=${firms.size} | mean Jaccard=${mean}`);
  console.log(`      ${famStr}`);
});

const singletons = clusters.filter(g => g.length === 1).length;
console.log(`\nSingletons: ${singletons} (${(singletons / N * 100).toFixed(1)}%)`);
console.log(`Clusters >=2: ${clusters.filter(g => g.length >= 2).length}`);
console.log(`Largest cluster: ${clusters[0].length} (${(clusters[0].length / N * 100).toFixed(1)}%)`);

// Cross-firm clusters
const crossFirm = clusters.filter(g => {
  if (g.length < 2) return false;
  return new Set(g.map(i => fps[i].firm)).size > 1;
});
console.log(`Cross-firm clusters: ${crossFirm.length}`);

// Identical pairs (Jaccard = 1.0)
const ident = [];
for (let i = 0; i < N; i++) for (let j = i + 1; j < N; j++) if (sim[i][j] >= 0.999) ident.push([i, j, sim[i][j]]);
console.log(`Identical (J>=0.999) pairs: ${ident.length}`);
ident.slice(0, 10).forEach(([i, j, s]) => console.log(`  ${fps[i].file}  ↔  ${fps[j].file}  (${s.toFixed(3)})`));

// Per-family intra variance
console.log('\nIntra-family (avg pairwise Jaccard among 6 attempts of same family):');
const byFam = {};
fps.forEach((f, i) => (byFam[f.family] = byFam[f.family] || []).push(i));
const famStats = Object.entries(byFam).map(([fam, idxs]) => {
  if (idxs.length < 2) return [fam, null, null];
  let sum = 0, cnt = 0, max = 0;
  for (let i = 0; i < idxs.length; i++) for (let j = i + 1; j < idxs.length; j++) {
    const s = sim[idxs[i]][idxs[j]];
    sum += s; cnt++; if (s > max) max = s;
  }
  return [fam, sum / cnt, max];
}).filter(x => x[1] !== null).sort((a, b) => b[1] - a[1]);
famStats.forEach(([f, m, mx]) => console.log(`  ${f.padEnd(28)}  mean=${m.toFixed(3)}  max=${mx.toFixed(3)}`));

// Top shared identifiers across whole corpus
const tokCount = {};
fps.forEach(f => f.tokens.forEach(t => tokCount[t] = (tokCount[t] || 0) + 1));
const top = Object.entries(tokCount).filter(([, n]) => n >= Math.floor(N * 0.6)).sort((a, b) => b[1] - a[1]).slice(0, 25);
console.log(`\nTop tokens present in >=60% of files (${Math.floor(N * 0.6)} files):`);
top.forEach(([t, n]) => console.log(`  ${n.toString().padStart(3)}/${N}  ${t}`));
