// End-to-end: extract fingerprints from todo_app HTMLs, build similarity matrix + clusters,
// render heatmap + network graph to PNG via Playwright (uses globally installed playwright-core).
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const REPO = path.resolve(__dirname, '..', '..');
const TASK = process.argv[2] || 'todo_app';
const DIR = path.join(REPO, 'benchmark', 'data', 'results', TASK);
const OUT_DIR = path.join(REPO, 'docs', 'articles', 'assets');
const THRESHOLD = 0.45;
const TASK_LABEL = TASK === 'todo_app' ? 'Todo App' : TASK === 'snake' ? 'Snake game' : TASK;

fs.mkdirSync(OUT_DIR, { recursive: true });

// --- 1. Extract ---
const files = fs.readdirSync(DIR).filter(f => f.endsWith('.html')).sort();

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

const fingerprints = files.map(f => ({
  file: f,
  family: familyOf(f),
  tokens: extract(fs.readFileSync(path.join(DIR, f), 'utf8')),
}));

// --- 2. Pairwise Jaccard ---
const N = fingerprints.length;
const sim = Array.from({ length: N }, () => new Float32Array(N));
for (let i = 0; i < N; i++) {
  sim[i][i] = 1;
  for (let j = i + 1; j < N; j++) {
    const a = fingerprints[i].tokens, b = fingerprints[j].tokens;
    let inter = 0;
    for (const t of a) if (b.has(t)) inter++;
    const s = a.size + b.size - inter === 0 ? 0 : inter / (a.size + b.size - inter);
    sim[i][j] = s;
    sim[j][i] = s;
  }
}

// --- 3. Single-link clustering ---
const parent = fingerprints.map((_, i) => i);
function find(x) { while (parent[x] !== x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }
function union(a, b) { const ra = find(a), rb = find(b); if (ra !== rb) parent[ra] = rb; }
for (let i = 0; i < N; i++) for (let j = i + 1; j < N; j++) if (sim[i][j] >= THRESHOLD) union(i, j);
const clusterMap = new Map();
for (let i = 0; i < N; i++) {
  const r = find(i);
  if (!clusterMap.has(r)) clusterMap.set(r, []);
  clusterMap.get(r).push(i);
}
const clusterGroups = [...clusterMap.values()].sort((a, b) => b.length - a.length);
const clusterIndex = new Int8Array(N);
clusterGroups.forEach((idxs, ci) => idxs.forEach(i => (clusterIndex[i] = ci)));

// Order files by cluster, then by family for nicer block-diagonal heatmap
const order = [...Array(N).keys()].sort((a, b) => {
  if (clusterIndex[a] !== clusterIndex[b]) return clusterIndex[a] - clusterIndex[b];
  if (fingerprints[a].family !== fingerprints[b].family) return fingerprints[a].family.localeCompare(fingerprints[b].family);
  return fingerprints[a].file.localeCompare(fingerprints[b].file);
});

// --- 4. Build viz HTML ---
const palette = ['#e63946', '#2a9d8f', '#f4a261', '#264653', '#9b5de5', '#118ab2', '#ef476f', '#06d6a0', '#ffd166', '#073b4c'];
const data = {
  files: fingerprints.map(f => f.file),
  families: fingerprints.map(f => f.family),
  cluster: Array.from(clusterIndex),
  order,
  sim: sim.map(row => Array.from(row)),
  palette,
  threshold: THRESHOLD,
  clusterSizes: clusterGroups.map(g => g.length),
};

const heatmapHtml = `<!doctype html><html><head><meta charset="utf-8"><style>
  body{margin:0;padding:32px;background:#fff;font:14px/1.4 -apple-system,Segoe UI,sans-serif;color:#222}
  h1{margin:0 0 8px;font-size:22px}
  .sub{color:#666;margin-bottom:20px}
  canvas{display:block;border:1px solid #ddd}
  .legend{margin-top:14px;display:flex;flex-wrap:wrap;gap:14px}
  .legend span{display:inline-flex;align-items:center;gap:6px}
  .swatch{width:14px;height:14px;border-radius:3px;display:inline-block}
  .scale{display:flex;align-items:center;gap:8px;margin-top:10px;color:#666;font-size:12px}
  .gradient{width:200px;height:12px;background:linear-gradient(to right,#fff,#0b3954)}
</style></head><body>
<h1>Code-similarity heatmap — ${TASK_LABEL} benchmark (${files.length} attempts)</h1>
<div class="sub">Pairwise Jaccard similarity over identifier sets (DOM IDs, CSS classes, JS function names, CSS variables). Files re-ordered by single-link cluster (Jaccard ≥ ${THRESHOLD}). Block-diagonal structure = a cluster.</div>
<canvas id="hm" width="900" height="900"></canvas>
<div class="scale"><span>0.0</span><span class="gradient"></span><span>1.0 Jaccard</span></div>
<div class="legend" id="leg"></div>
<script>
const D = ${JSON.stringify(data)};
const cv = document.getElementById('hm');
const ctx = cv.getContext('2d');
const N = D.files.length;
const cell = Math.floor((cv.width - 60) / N);
const off = 60;
ctx.fillStyle = '#fff'; ctx.fillRect(0,0,cv.width,cv.height);
function lerp(a,b,t){return a+(b-a)*t}
function color(s){
  // white -> deep teal
  const r = Math.round(lerp(255, 11, s));
  const g = Math.round(lerp(255, 57, s));
  const b = Math.round(lerp(255, 84, s));
  return 'rgb('+r+','+g+','+b+')';
}
for (let i = 0; i < N; i++) {
  for (let j = 0; j < N; j++) {
    const a = D.order[i], b = D.order[j];
    ctx.fillStyle = color(D.sim[a][b]);
    ctx.fillRect(off + j*cell, off + i*cell, cell, cell);
  }
}
// Cluster strip (left + top)
for (let i = 0; i < N; i++) {
  const a = D.order[i];
  ctx.fillStyle = D.palette[D.cluster[a] % D.palette.length];
  ctx.fillRect(off - 14, off + i*cell, 10, cell);
  ctx.fillRect(off + i*cell, off - 14, cell, 10);
}
// Cluster boundary lines
ctx.strokeStyle = '#000'; ctx.lineWidth = 0.7;
let prev = -1;
for (let i = 0; i < N; i++) {
  const ci = D.cluster[D.order[i]];
  if (ci !== prev && i > 0) {
    ctx.beginPath(); ctx.moveTo(off, off + i*cell); ctx.lineTo(off + N*cell, off + i*cell); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(off + i*cell, off); ctx.lineTo(off + i*cell, off + N*cell); ctx.stroke();
  }
  prev = ci;
}
// Outer frame
ctx.strokeStyle = '#444'; ctx.lineWidth = 1;
ctx.strokeRect(off, off, N*cell, N*cell);
// Legend (clusters): show cluster id + count + dominant family
const leg = document.getElementById('leg');
function dominantFamily(ci){
  const fams = {};
  for (let i=0;i<D.cluster.length;i++) if (D.cluster[i]===ci) fams[D.families[i]]=(fams[D.families[i]]||0)+1;
  return Object.entries(fams).sort((a,b)=>b[1]-a[1]).slice(0,3).map(([f,n])=>f+'×'+n).join(', ');
}
D.clusterSizes.forEach((n, ci) => {
  if (n < 2) return;
  const div = document.createElement('span');
  div.innerHTML = '<span class="swatch" style="background:'+D.palette[ci%D.palette.length]+'"></span>Cluster '+(ci+1)+' ('+n+'): '+dominantFamily(ci);
  leg.appendChild(div);
});
const sing = D.clusterSizes.filter(n => n === 1).length;
if (sing) {
  const div = document.createElement('span'); div.style.color='#666';
  div.textContent = sing + ' singleton(s)';
  leg.appendChild(div);
}
</script></body></html>`;

const networkHtml = `<!doctype html><html><head><meta charset="utf-8"><style>
  body{margin:0;padding:32px;background:#fff;font:14px/1.4 -apple-system,Segoe UI,sans-serif;color:#222}
  h1{margin:0 0 8px;font-size:22px}
  .sub{color:#666;margin-bottom:20px}
  svg{display:block;background:#fafafa;border:1px solid #ddd;border-radius:6px}
  .label{font:11px sans-serif;fill:#222}
  .fam{font:10px sans-serif;fill:#666}
</style></head><body>
<h1>Cluster map — ${TASK_LABEL}: who writes the same code?</h1>
<div class="sub">Each node = one HTML attempt. Edge drawn when Jaccard ≥ ${THRESHOLD}. Node color = cluster. Layout: clusters arranged in a ring, families grouped within each cluster.</div>
<svg id="g" width="1100" height="900"></svg>
<script>
const D = ${JSON.stringify(data)};
const W = 1100, H = 900, CX = W/2, CY = H/2;
const svg = document.getElementById('g');
const SVG = 'http://www.w3.org/2000/svg';
const N = D.files.length;
// Group by cluster, then by family
const clusters = {};
for (let i = 0; i < N; i++) {
  const ci = D.cluster[i];
  (clusters[ci] = clusters[ci] || []).push(i);
}
const clusterIds = Object.keys(clusters).map(Number).sort((a,b)=>clusters[b].length-clusters[a].length);

// Place big clusters around the center, small ones outside
const positions = new Array(N);
const ringR = 360;
clusterIds.forEach((ci, idx) => {
  const members = clusters[ci];
  const angle0 = (idx / clusterIds.length) * Math.PI * 2;
  const cR = members.length > 1 ? 80 + Math.min(120, members.length * 2.5) : 30;
  const cx = CX + Math.cos(angle0) * (members.length > 1 ? ringR : ringR + 100);
  const cy = CY + Math.sin(angle0) * (members.length > 1 ? ringR : ringR + 100);
  // Sort members by family for nicer visual grouping
  const sorted = [...members].sort((a,b)=>D.families[a].localeCompare(D.families[b]));
  sorted.forEach((m, k) => {
    const a = (k / sorted.length) * Math.PI * 2;
    positions[m] = { x: cx + Math.cos(a) * cR, y: cy + Math.sin(a) * cR, ci, cx, cy };
  });
});

// Draw edges (Jaccard >= threshold), faint
for (let i = 0; i < N; i++) {
  for (let j = i + 1; j < N; j++) {
    if (D.sim[i][j] < D.threshold) continue;
    const sameCluster = D.cluster[i] === D.cluster[j];
    const e = document.createElementNS(SVG, 'line');
    e.setAttribute('x1', positions[i].x); e.setAttribute('y1', positions[i].y);
    e.setAttribute('x2', positions[j].x); e.setAttribute('y2', positions[j].y);
    e.setAttribute('stroke', sameCluster ? D.palette[D.cluster[i] % D.palette.length] : '#ccc');
    e.setAttribute('stroke-opacity', sameCluster ? Math.min(0.5, D.sim[i][j]) : 0.15);
    e.setAttribute('stroke-width', sameCluster ? 1 : 0.5);
    svg.appendChild(e);
  }
}
// Draw nodes
for (let i = 0; i < N; i++) {
  const p = positions[i];
  const c = document.createElementNS(SVG, 'circle');
  c.setAttribute('cx', p.x); c.setAttribute('cy', p.y); c.setAttribute('r', 6);
  c.setAttribute('fill', D.palette[D.cluster[i] % D.palette.length]);
  c.setAttribute('stroke', '#222'); c.setAttribute('stroke-width', 0.5);
  svg.appendChild(c);
}
// Cluster labels
clusterIds.forEach((ci, idx) => {
  if (clusters[ci].length < 2) return;
  const p = positions[clusters[ci][0]];
  const t = document.createElementNS(SVG, 'text');
  t.setAttribute('x', p.cx); t.setAttribute('y', p.cy - (clusters[ci].length>1 ? 80+Math.min(120,clusters[ci].length*2.5)+18 : 50));
  t.setAttribute('text-anchor', 'middle');
  t.setAttribute('class', 'label');
  t.setAttribute('font-weight', 'bold');
  t.setAttribute('fill', D.palette[ci % D.palette.length]);
  t.textContent = 'Cluster ' + (ci+1) + ' (' + clusters[ci].length + ')';
  svg.appendChild(t);
  // Family list
  const families = [...new Set(clusters[ci].map(i => D.families[i]))].sort();
  families.forEach((f, k) => {
    const ft = document.createElementNS(SVG, 'text');
    ft.setAttribute('x', p.cx); ft.setAttribute('y', p.cy - (clusters[ci].length>1 ? 80+Math.min(120,clusters[ci].length*2.5)+4 : 36) + k*12);
    ft.setAttribute('text-anchor', 'middle');
    ft.setAttribute('class', 'fam');
    ft.textContent = f;
    svg.appendChild(ft);
  });
});
</script></body></html>`;

const tmpDir = path.join(OUT_DIR, '_tmp');
fs.mkdirSync(tmpDir, { recursive: true });
const heatmapPage = path.join(tmpDir, 'heatmap.html');
const networkPage = path.join(tmpDir, 'network.html');
fs.writeFileSync(heatmapPage, heatmapHtml);
fs.writeFileSync(networkPage, networkHtml);

// --- 5. Render via Playwright ---
const renderScript = `
const { chromium } = require('playwright-core');
const path = require('path');
(async () => {
  const [, , page1, out1, page2, out2] = process.argv;
  const browser = await chromium.launch();
  for (const [src, out, w, h] of [[page1, out1, 1000, 1050], [page2, out2, 1180, 980]]) {
    const ctx = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 2 });
    const page = await ctx.newPage();
    await page.goto('file://' + path.resolve(src), { waitUntil: 'load' });
    await page.waitForTimeout(500);
    await page.screenshot({ path: out, fullPage: true });
    await ctx.close();
  }
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
`;
const slug = TASK === 'todo_app' ? 'todo' : TASK;
const heatmapPng = path.join(OUT_DIR, `${slug}-similarity-heatmap.png`);
const networkPng = path.join(OUT_DIR, `${slug}-similarity-network.png`);
const r = spawnSync('node', ['-e', renderScript, 'render', heatmapPage, heatmapPng, networkPage, networkPng], {
  env: { ...process.env, NODE_PATH: 'C:\\Program Files\\nodejs\\node_modules' },
  stdio: 'inherit',
});
if (r.status !== 0) process.exit(r.status);

// Cleanup tmp HTMLs (keep PNGs)
fs.rmSync(tmpDir, { recursive: true, force: true });

console.log('\nWrote:');
console.log('  ' + heatmapPng);
console.log('  ' + networkPng);
console.log('\nClusters:');
clusterGroups.forEach((g, ci) => {
  if (g.length < 2) return;
  console.log(`  ${ci+1}: ${g.length} files`);
});
