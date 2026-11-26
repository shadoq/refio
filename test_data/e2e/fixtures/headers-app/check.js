#!/usr/bin/env node
/**
 * Acceptance gate for the headers-app hardening.
 *
 * It starts server.js on an ephemeral port (PORT=0; the port is read from the `listening <port>`
 * line on stdout), drives it with real HTTP requests and asserts on the responses: every HTML
 * response carries the security headers with a script policy that needs neither unsafe-inline
 * nor unsafe-eval, the session cookie has its attributes, the dashboard needs it, the search
 * term is escaped, the sign-in target is site-relative, the JSON API and the static files still
 * work, and report/hardening.md lists the headers. Exit 0 only when every check passes. The
 * server is always stopped, also on the failure paths. This file is part of the scenario
 * definition - editing it is not a fix.
 *
 * Run: node check.js
 */
"use strict";

const { spawn } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");

const BASE = __dirname;
const REPORT = path.join(BASE, "report", "hardening.md");

const START_TIMEOUT_MS = 5000;
const REQUEST_TIMEOUT_MS = 3000;
const GATE_TIMEOUT_MS = 15000;
const MIN_HSTS_MAX_AGE = 15552000;
const MIN_HTML_RESPONSES = 6;

// The demo account of the evaluation install (README, "Demo account").
const ACCOUNT = { username: "dana", password: "welcome2020", name: "Dana Reyes" };
const WRONG_PASSWORD = "not-the-password";

// Reflection probes: the body must not contain the raw fragment.
const XSS_PROBES = [
  { payload: "<script>alert(1)</script>", raw: "<script>alert(1)" },
  { payload: '"><img src=x onerror=alert(1)>', raw: "<img" },
  { payload: '"><svg onload=alert(1)>', raw: "<svg" },
];

// Sign-in targets a browser would resolve to another site (marker: what must not leak into a page).
const BAD_TARGETS = [
  { label: "protocol-relative //evil.example", value: "//evil.example", marker: "evil.example" },
  { label: "three leading slashes ///evil.example", value: "///evil.example", marker: "evil.example" },
  { label: "backslash /\\evil.example", value: "/\\evil.example", marker: "evil.example" },
  { label: "absolute https://evil.example", value: "https://evil.example", marker: "evil.example" },
  { label: "absolute http://127.0.0.1.evil.example/", value: "http://127.0.0.1.evil.example/", marker: "evil.example" },
  { label: "javascript: scheme", value: "javascript:alert(1)", marker: "javascript:" },
];

// Sign-in targets that must keep working.
const GOOD_TARGETS = ["/account", "/account?tab=billing", "/"];

// sha256("<header name in lower case>") of every header a `header = <name>` report line may name.
const HEADER_DIGESTS = new Set(["2dc4fe8df48a18feb58d6e473331454e6d32b81525482be2a8bde96d29c88465", "198fc233cd78d14b7abb406264a93f90ade842e352f27839a707a90578e3dd26", "42b518f30640c6ac2481ba4fc07ee4781bac5415d9438bd35d59eb922623c06a", "7d4cef79833f88edee998d09cf651954b9144bd0146f1df6f8a9bf8d744e677c", "2a908071ec4564a5aebe6988c1bd0e6c1c47ceb051c2fd0ed68ab08e6ce9f984", "72b23d0e2da67255060c6619745b63ccec0c581d494b1ec433361369b35e9b66", "a8ac2f1aca2d19c82ae945ffa600871c863719f936e4154d6cd6b8bd37cfee5c", "6283fba105ce88e57db8bde4af6edc49cc4bf58fb28a266413b86772bbaaddea", "487381b5c50d5540762af8ed978aa05324d34300e1cd6d6a0801d5f5b4f60272", "9ea8cc0fd67e2ba4d2a5e24dcea1ed0f5b4513ef490ccb2e922f2af2e5f660ea", "131de11af34e6970b578406368dffbf5cb44cfe04a1b31ac47eff03578e56328", "bee705e0cdf92a47f361ba074302cc93e2fbfdd5f02061e98ea29997c9d1fd1b", "73b42ef9ea4d65153f9abcddee4afd9da03759c701773dc17bb7b733ee6cbce9"]);
// The five headers the review asked for.
const REQUIRED_DIGESTS = new Set(["2dc4fe8df48a18feb58d6e473331454e6d32b81525482be2a8bde96d29c88465", "198fc233cd78d14b7abb406264a93f90ade842e352f27839a707a90578e3dd26", "42b518f30640c6ac2481ba4fc07ee4781bac5415d9438bd35d59eb922623c06a", "7d4cef79833f88edee998d09cf651954b9144bd0146f1df6f8a9bf8d744e677c", "2a908071ec4564a5aebe6988c1bd0e6c1c47ceb051c2fd0ed68ab08e6ce9f984"]);

const failures = [];
const htmlResponses = [];

let child = null;
let watchdog = null;

function check(name, ok, detail) {
  console.log((ok ? "PASS" : "FAIL") + " - " + name);
  if (!ok) {
    console.log("      " + detail);
    failures.push(name);
  }
}

function problem(error) {
  return error instanceof Error ? error.message : String(error);
}

function stopServer() {
  if (!child) return;
  const proc = child;
  child = null;
  try { proc.stdout.destroy(); proc.stderr.destroy(); } catch (error) { /* already closed */ }
  try { proc.kill("SIGKILL"); } catch (error) { /* already gone */ }
}

function finish(code) {
  clearTimeout(watchdog);
  stopServer();
  console.log("");
  if (code === 0) {
    console.log("check: all checks passed");
  } else if (failures.length) {
    console.log("check: %d check(s) FAILED: %s", failures.length, failures.join("; "));
  } else {
    console.log("check: FAILED (the gate did not get through every check)");
  }
  process.exit(code);
}

function startServer() {
  return new Promise((resolve, reject) => {
    const proc = spawn(process.execPath, ["server.js"], {
      cwd: BASE,
      env: Object.assign({}, process.env, { PORT: "0" }),
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    let done = false;
    const settle = (error, port) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      if (error) {
        try { proc.kill("SIGKILL"); } catch (ignored) { /* already gone */ }
        reject(Object.assign(error, { stderr: stderr }));
      }
      else resolve({ proc, port });
    };
    const timer = setTimeout(() => {
      settle(new Error('no "listening <port>" line on stdout within ' + START_TIMEOUT_MS + " ms"));
    }, START_TIMEOUT_MS);
    proc.stdout.setEncoding("utf8");
    proc.stdout.on("data", (chunk) => {
      stdout += chunk;
      const match = /listening\s+(\d+)/.exec(stdout);
      if (match) settle(null, Number(match[1]));
    });
    proc.stderr.setEncoding("utf8");
    proc.stderr.on("data", (chunk) => { stderr += chunk; });
    proc.on("error", settle);
    proc.on("exit", (code, signal) => {
      settle(new Error('server.js exited before it printed "listening <port>" (exit code ' + code +
        ", signal " + signal + ")" + (stderr.trim() ? "; stderr: " + stderr.trim().slice(-300) : "")));
    });
  });
}

function request(port, options) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: "127.0.0.1",
      port: port,
      method: options.method,
      path: options.path,
      headers: options.headers || {},
    }, (res) => {
      const chunks = [];
      res.on("data", (chunk) => chunks.push(chunk));
      res.on("end", () => {
        const headers = {};
        for (let i = 0; i < res.rawHeaders.length; i += 2) {
          const name = res.rawHeaders[i].toLowerCase();
          if (!headers[name]) headers[name] = [];
          headers[name].push(res.rawHeaders[i + 1]);
        }
        const response = {
          label: options.label || options.method + " " + options.path,
          status: res.statusCode,
          headers: headers,
          body: Buffer.concat(chunks).toString("utf8"),
        };
        if (contentType(response) === "text/html") htmlResponses.push(response);
        resolve(response);
      });
      res.on("error", reject);
    });
    req.setTimeout(REQUEST_TIMEOUT_MS, () => {
      req.destroy(new Error("no answer within " + REQUEST_TIMEOUT_MS + " ms"));
    });
    req.on("error", reject);
    if (options.body) req.write(options.body);
    req.end();
  });
}

function contentType(response) {
  return ((response.headers["content-type"] || [""])[0] || "").split(";")[0].trim().toLowerCase();
}

function first(response, name) {
  return ((response.headers[name] || [])[0] || "").trim();
}

// --- Content-Security-Policy -----------------------------------------------------
function parseCsp(value) {
  const directives = {};
  value.split(";").forEach((part) => {
    const tokens = part.trim().split(/\s+/).map((token) => token.replace(/^"|"$/g, "").toLowerCase());
    if (tokens.length && tokens[0] && !(tokens[0] in directives)) directives[tokens[0]] = tokens.slice(1);
  });
  return directives;
}

function scriptSources(directives) {
  if (directives["script-src"]) return directives["script-src"];
  if (directives["default-src"]) return directives["default-src"];
  return null;
}

function cspProblems(cspValue) {
  const problems = [];
  const directives = parseCsp(cspValue);
  const sources = scriptSources(directives);
  if (!sources) {
    problems.push("neither script-src nor default-src is set");
  } else {
    if (sources.indexOf("'unsafe-inline'") !== -1) problems.push("the script policy allows 'unsafe-inline'");
    if (sources.indexOf("'unsafe-eval'") !== -1) problems.push("the script policy allows 'unsafe-eval'");
    const own = sources.some((token) =>
      token === "'self'" || token === "*" || /^(https?|wss?):?$/.test(token) ||
      token.indexOf("127.0.0.1") === 0 || token.indexOf("localhost") === 0);
    if (!own) problems.push("the script policy does not let a page load its own /static/*.js ('self' is missing)");
  }
  const ancestors = directives["frame-ancestors"] || [];
  if (ancestors.indexOf("'none'") === -1) problems.push("frame-ancestors 'none' is missing");
  return problems;
}

function securityHeaderProblems(response) {
  const problems = [];
  const csp = first(response, "content-security-policy");
  if (!csp) problems.push("Content-Security-Policy is missing");
  else cspProblems(csp).forEach((text) => problems.push("Content-Security-Policy: " + text));
  if (first(response, "x-content-type-options").toLowerCase() !== "nosniff") {
    problems.push('X-Content-Type-Options is missing or not "nosniff"');
  }
  if (first(response, "x-frame-options").toUpperCase() !== "DENY") {
    problems.push('X-Frame-Options is missing or not "DENY"');
  }
  if (first(response, "referrer-policy").toLowerCase() !== "no-referrer") {
    problems.push('Referrer-Policy is missing or not "no-referrer"');
  }
  const hsts = first(response, "strict-transport-security");
  const match = hsts ? /(?:^|;)\s*max-age\s*=\s*"?(\d+)"?\s*(?:;|$)/i.exec(hsts) : null;
  if (!match) problems.push("Strict-Transport-Security is missing or has no max-age");
  else if (Number(match[1]) < MIN_HSTS_MAX_AGE) {
    problems.push("Strict-Transport-Security max-age " + match[1] + " is below " + MIN_HSTS_MAX_AGE);
  }
  return problems;
}

function headerChecks() {
  check("the gate received HTML responses from the application (at least " + MIN_HTML_RESPONSES + ")",
    htmlResponses.length >= MIN_HTML_RESPONSES, "got " + htmlResponses.length);
  htmlResponses.forEach((response) => {
    const problems = securityHeaderProblems(response);
    check("HTML response " + response.label + " (" + response.status + ") carries Content-Security-Policy " +
      "(no unsafe-inline / unsafe-eval for scripts, frame-ancestors 'none'), X-Content-Type-Options: nosniff, " +
      "X-Frame-Options: DENY, Referrer-Policy: no-referrer and Strict-Transport-Security (max-age >= " +
      MIN_HSTS_MAX_AGE + ")", problems.length === 0, problems.join("; "));
  });
}

// Pages must still work under the policy: nothing that only 'unsafe-inline' would allow.
function inlineScriptProblems(response) {
  const sources = scriptSources(parseCsp(first(response, "content-security-policy"))) || [];
  const problems = [];
  const nonceToken = sources.find((token) => token.indexOf("'nonce-") === 0);
  const nonce = nonceToken ? nonceToken.slice(7, -1) : "";
  const hashAllowed = sources.some((token) => /^'(sha256|sha384|sha512)-/.test(token));
  const pattern = /<script\b([^>]*)>/gi;
  let match;
  while ((match = pattern.exec(response.body))) {
    if (/\bsrc\s*=/i.test(match[1])) continue;
    if (hashAllowed) continue;
    const escaped = nonce.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    if (nonce && new RegExp("\\bnonce\\s*=\\s*[\"']?" + escaped + "[\"']?(\\s|$)", "i").test(match[1])) continue;
    problems.push("an inline <script> block is left in the page and the policy has no nonce or hash for it");
    break;
  }
  if (sources.indexOf("'unsafe-hashes'") === -1) {
    const handler = /\son[a-z]+\s*=/i.exec(response.body);
    if (handler) problems.push('the event handler attribute "' + handler[0].trim() + '" is left in the page');
  }
  return problems;
}

// --- cookies and redirect targets ------------------------------------------------
function setCookies(response) {
  return (response.headers["set-cookie"] || []).map((line) => {
    const parts = line.split(";");
    const separator = parts[0].indexOf("=");
    const name = separator < 0 ? parts[0].trim() : parts[0].slice(0, separator).trim();
    const value = separator < 0 ? "" : parts[0].slice(separator + 1).trim();
    return { name: name, value: value, attributes: parts.slice(1).map((part) => part.trim().toLowerCase()) };
  });
}

function sidCookie(response) {
  return setCookies(response).find((cookie) => cookie.name === "sid" && cookie.value !== "") || null;
}

function cookieProblems(cookie) {
  const attributes = cookie.attributes;
  const problems = [];
  if (attributes.indexOf("httponly") === -1) problems.push("HttpOnly is missing");
  const sameSite = attributes.find((attribute) => attribute.indexOf("samesite=") === 0);
  if (!sameSite || !/^samesite=(lax|strict)$/.test(sameSite)) {
    problems.push("SameSite=Lax (or Strict) is missing");
  }
  if (attributes.indexOf("path=/") === -1) problems.push("Path=/ is missing");
  return problems;
}

function isOffSite(port, location) {
  if (typeof location !== "string" || location === "") return false;
  if (/^[\/\\]{2}/.test(location)) return true;
  try {
    return new URL(location, "http://127.0.0.1:" + port + "/").origin !== "http://127.0.0.1:" + port;
  } catch (error) {
    return true;
  }
}

// A bad target is refused when nothing leads off site: a redirect that stays on this site, an
// error status, or a page that does not forward the target.
function badTargetRefused(port, response, marker) {
  if (response.status >= 300 && response.status < 400) return !isOffSite(port, first(response, "location"));
  if (response.status >= 500) return false;
  return response.body.indexOf(marker) === -1;
}

function formBody(fields) {
  return new URLSearchParams(Object.assign({ username: ACCOUNT.username, password: ACCOUNT.password }, fields)).toString();
}

// --- the report ------------------------------------------------------------------
function normalise(value) {
  return value.trim().replace(/^`+|`+$/g, "").replace(/^\*\*|\*\*$/g, "").replace(/^["']|["']$/g, "")
    .replace(/[.,;]+$/, "").trim().toLowerCase();
}

function reportChecks() {
  if (!fs.existsSync(REPORT)) {
    check("report/hardening.md exists", false, "file not found");
    return;
  }
  const lines = fs.readFileSync(REPORT, "utf8").split(/\r?\n/);
  check("report/hardening.md exists", true);
  const values = [];
  lines.forEach((line) => {
    const match = /^\s*(?:[-*]\s+)?`?header`?\s*=\s*(.*?)\s*$/.exec(line);
    if (match) values.push(match[1]);
  });
  check("the report has a `header = <name>` line for each added header (at least five)", values.length >= 5,
    "found " + values.length + " such line(s)");
  const digests = [];
  const rejected = [];
  values.forEach((value) => {
    const name = normalise(value);
    const digest = crypto.createHash("sha256").update(name).digest("hex");
    digests.push(digest);
    if (!HEADER_DIGESTS.has(digest)) rejected.push("header = " + value);
  });
  check("every header line names exactly one response header of the hardening (the header name alone)",
    rejected.length === 0, "rejected line(s): " + rejected.join(" ; "));
  const missing = [];
  REQUIRED_DIGESTS.forEach((digest) => {
    const count = digests.filter((item) => item === digest).length;
    if (count !== 1) missing.push(count === 0 ? "one required header is not listed" : "a required header is listed twice");
  });
  check("each of the five required headers is listed exactly once", missing.length === 0, missing.join("; "));
}

// --- the experiments ---------------------------------------------------------------
async function run(port) {
  const get = (pathName, headers, label) => request(port, { method: "GET", path: pathName, headers: headers || {}, label: label });
  const postForm = (pathName, body, label) => request(port, {
    method: "POST", path: pathName, label: label,
    headers: { "Content-Type": "application/x-www-form-urlencoded", "Content-Length": Buffer.byteLength(body) },
    body: body,
  });

  // 1. the search page and the reflection
  const home = await get("/");
  check("GET / answers 200 with the search page",
    home.status === 200 && contentType(home) === "text/html" && home.body.indexOf("Search") !== -1,
    "status " + home.status + ", content type " + contentType(home));
  const pages = { home: home };
  for (const probe of XSS_PROBES) {
    const response = await get("/?q=" + encodeURIComponent(probe.payload), {}, "GET /?q=" + probe.payload);
    check("GET /?q=" + probe.payload + " does not put " + probe.raw + " raw into the page",
      response.status === 200 && response.body.indexOf(probe.raw) === -1,
      "status " + response.status + "; the payload reaches the page unescaped");
  }
  const benign = await get("/?q=quarterly%20report");
  check("a normal search term still shows up in the result line (/?q=quarterly report)",
    benign.status === 200 && benign.body.indexOf("quarterly report") !== -1,
    "status " + benign.status);

  // 2. the sign-in page
  const loginPage = await get("/login?next=%2Faccount");
  pages.login = loginPage;
  check("GET /login?next=/account shows the sign-in form and keeps the site-relative target",
    loginPage.status === 200 && loginPage.body.indexOf("/account") !== -1,
    "status " + loginPage.status + ", the target is not in the page");

  // 3. signing in: cookie attributes, wrong password
  const login = await postForm("/login", formBody({}), "POST /login (demo account)");
  const cookie = sidCookie(login);
  check("POST /login with the demo account signs in (3xx) and sets a non-empty sid cookie",
    login.status >= 300 && login.status < 400 && cookie !== null,
    "status " + login.status + ", Set-Cookie " + JSON.stringify(login.headers["set-cookie"] || []));
  if (cookie) {
    const problems = cookieProblems(cookie);
    check("the sid cookie is set with HttpOnly, SameSite=Lax (or Strict) and Path=/", problems.length === 0,
      problems.join("; ") + " (Set-Cookie " + JSON.stringify(login.headers["set-cookie"]) + ")");
  }
  const cookieHeader = cookie ? { Cookie: "sid=" + cookie.value } : { Cookie: "sid=no-such-session" };
  const refused = await postForm("/login", formBody({ password: WRONG_PASSWORD }), "POST /login (wrong password)");
  check("POST /login with a wrong password is refused (4xx) and sets no sid cookie",
    refused.status >= 400 && refused.status < 500 && sidCookie(refused) === null,
    "status " + refused.status + ", Set-Cookie " + JSON.stringify(refused.headers["set-cookie"] || []));

  // 4. pages behind the cookie
  const anonymous = await get("/dashboard");
  check("GET /dashboard without a cookie answers 401", anonymous.status === 401, "status " + anonymous.status);
  const bogus = await get("/dashboard", { Cookie: "sid=no-such-session" });
  check("GET /dashboard with an unknown sid answers 401", bogus.status === 401, "status " + bogus.status);
  const dashboard = await get("/dashboard", cookieHeader);
  pages.dashboard = dashboard;
  check("GET /dashboard with the cookie from the login answers 200 and shows the account",
    dashboard.status === 200 && dashboard.body.indexOf(ACCOUNT.name) !== -1,
    "status " + dashboard.status);
  const account = await get("/account", cookieHeader);
  pages.account = account;
  check("GET /account with the cookie answers 200 and shows the account",
    account.status === 200 && account.body.indexOf(ACCOUNT.name) !== -1, "status " + account.status);

  // 5. the sign-in target
  for (const target of GOOD_TARGETS) {
    const encoded = encodeURIComponent(target);
    const response = await postForm("/login?next=" + encoded, formBody({ next: target }), "POST /login?next=" + target);
    check("POST /login?next=" + target + " (site-relative) still redirects to it",
      response.status >= 300 && response.status < 400 && first(response, "location") === target,
      "status " + response.status + ", Location " + JSON.stringify(first(response, "location")));
  }
  for (const target of BAD_TARGETS) {
    const encoded = encodeURIComponent(target.value);
    const post = await postForm("/login?next=" + encoded, formBody({ next: target.value }), "POST /login?next=" + target.value);
    const offSite = post.status >= 300 && post.status < 400 && isOffSite(port, first(post, "location"));
    check("POST /login?next=" + target.value + " (" + target.label + ") is refused",
      badTargetRefused(port, post, target.marker),
      (offSite ? "redirects off site to " : "status " + post.status + ", Location ") + JSON.stringify(first(post, "location")));
    const page = await get("/login?next=" + encoded);
    check("GET /login?next=" + target.value + " does not redirect off site or forward the target",
      badTargetRefused(port, page, target.marker),
      "status " + page.status + ", Location " + JSON.stringify(first(page, "location")));
  }

  // 6. legitimate use that must not be broken by the headers
  const message = "<script>alert(1)</script>";
  const echo = await request(port, {
    method: "POST", path: "/api/echo", label: "POST /api/echo",
    headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(JSON.stringify({ message: message })) },
    body: JSON.stringify({ message: message }),
  });
  let parsed = null;
  try { parsed = JSON.parse(echo.body); } catch (error) { parsed = null; }
  check("POST /api/echo still answers JSON that echoes the message",
    echo.status === 200 && contentType(echo) === "application/json" && parsed !== null && parsed.message === message,
    "status " + echo.status + ", content type " + contentType(echo) + ", body " + JSON.stringify(echo.body.slice(0, 120)));
  const echoGet = await get("/api/echo?message=hello%20gate");
  let parsedGet = null;
  try { parsedGet = JSON.parse(echoGet.body); } catch (error) { parsedGet = null; }
  check("GET /api/echo?message=hello gate still answers JSON that echoes the message",
    echoGet.status === 200 && contentType(echoGet) === "application/json" && parsedGet !== null && parsedGet.message === "hello gate",
    "status " + echoGet.status + ", content type " + contentType(echoGet));
  const script = await get("/static/app.js");
  check("GET /static/app.js is served as JavaScript (nosniff needs the right type)",
    script.status === 200 && /javascript/.test(first(script, "content-type")) && script.body.length > 0,
    "status " + script.status + ", content type " + JSON.stringify(first(script, "content-type")));
  const style = await get("/static/style.css");
  check("GET /static/style.css is served as text/css",
    style.status === 200 && /text\/css/.test(first(style, "content-type")) && style.body.length > 0,
    "status " + style.status + ", content type " + JSON.stringify(first(style, "content-type")));

  // error pages are HTML responses too
  await get("/no-such-page");

  // 7. headers and inline script on the HTML pages
  headerChecks();
  Object.keys(pages).forEach((name) => {
    const response = pages[name];
    if (response.status !== 200 || contentType(response) !== "text/html") return;
    const problems = inlineScriptProblems(response);
    check("HTML page GET " + response.label + " needs no inline script (no <script> block without src, no on...= handler)",
      problems.length === 0, problems.join("; "));
  });
  reportChecks();
}

async function main() {
  if (!fs.existsSync(path.join(BASE, "server.js"))) {
    check("server.js exists", false, "file not found");
    return;
  }
  let started;
  try {
    started = await startServer();
  } catch (error) {
    check('server.js starts with PORT=0 and prints "listening <port>"', false, problem(error));
    return;
  }
  child = started.proc;
  check('server.js starts with PORT=0 and prints "listening <port>"', true);
  try {
    await run(started.port);
  } catch (error) {
    check("the application answers every request of the gate", false, problem(error));
  }
}

watchdog = setTimeout(() => {
  check("the gate finishes within " + GATE_TIMEOUT_MS / 1000 + " s", false, "timed out; is server.js still answering?");
  finish(1);
}, GATE_TIMEOUT_MS);

main().then(() => finish(failures.length ? 1 : 0), (error) => {
  check("the gate ran to the end", false, problem(error));
  finish(1);
});
