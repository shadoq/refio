// headers-app: the support team's status portal.
//
// Start it with `node server.js` (PORT environment variable, default 3000). On start-up it
// prints `listening <port>` so that smoke scripts can find the port; PORT=0 asks the OS for a
// free one.
//
// Hardened after the security review (report/hardening.md): every response carries the security
// headers, the session cookie is protected, the search term is escaped, the sign-in target has
// to be a path on this site, and all script lives in public/app.js so that the
// Content-Security-Policy needs no 'unsafe-inline'.
"use strict";

const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");

const PUBLIC_DIR = path.join(__dirname, "public");
const PORT = Number(process.env.PORT || 3000);

// The demo account of the evaluation install (README, "Demo account").
const USERS = {
  dana: { password: "welcome2020", name: "Dana Reyes" },
};

const MIME = {
  ".css": "text/css",
  ".js": "text/javascript",
  ".svg": "image/svg+xml",
};

// A year, above the six months that browsers ask for before they pin a host.
const HSTS_MAX_AGE = 31536000;
const SESSION_MAX_AGE = 8 * 60 * 60;

// The session cookie: name plus the attributes every Set-Cookie of this app sends.
const SESSION_COOKIE = { name: "sid", attributes: "HttpOnly; SameSite=Lax; Path=/", maxAge: SESSION_MAX_AGE };

// Sent with every response (pages, redirects, the API and static files alike).
const SECURITY_HEADERS = {
  "Content-Security-Policy": [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self' data:",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join("; "),
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  "Referrer-Policy": "no-referrer",
  "Strict-Transport-Security": "max-age=" + HSTS_MAX_AGE,
};

function layout(page, title, content) {
  return [
    "<!doctype html>",
    '<html lang="en">',
    "<head>",
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    "<title>" + title + " - Support portal</title>",
    '<link rel="stylesheet" href="/static/style.css">',
    '<script src="/static/app.js" defer></script>',
    "</head>",
    '<body data-page="' + page + '">',
    '<header><a class="brand" href="/">Support portal</a>' +
      '<nav><a href="/">Search</a><a href="/dashboard">Dashboard</a><a href="/account">Account</a></nav></header>',
    "<main>",
    content,
    "</main>",
    "</body>",
    "</html>",
  ].join("\n");
}

function escapeHtml(value) {
  const entities = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
  return String(value).replace(/[&<>"']/g, (character) => entities[character]);
}

// A sign-in target is accepted only when it is a path on this site: it starts with exactly one
// slash and contains nothing a browser would turn into a second slash (an authority), such as a
// backslash, or a scheme.
function safeNext(value) {
  if (typeof value !== "string" || value === "" || value.length > 2000) return null;
  let decoded = value;
  try {
    decoded = decodeURIComponent(value);
  } catch (error) {
    return null;
  }
  if (!decoded.startsWith("/") || decoded.startsWith("//") || decoded.startsWith("/\\")) return null;
  if (/[\r\n\\]/.test(decoded) || /^\/[a-z][a-z0-9+.-]*:/i.test(decoded)) return null;
  return decoded;
}

function send(res, status, type, body, extraHeaders) {
  const headers = Object.assign({}, SECURITY_HEADERS, extraHeaders || {});
  headers["Content-Type"] = type;
  headers["Content-Length"] = Buffer.byteLength(body);
  res.writeHead(status, headers);
  res.end(body);
}

function sendHtml(res, page, status, title, content) {
  send(res, status, "text/html; charset=utf-8", layout(page, title, content));
}

function redirect(res, location) {
  send(res, 303, "text/plain; charset=utf-8", "See " + location, { Location: location });
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function parseCookies(req) {
  const cookies = {};
  for (const part of (req.headers.cookie || "").split(";")) {
    const index = part.indexOf("=");
    if (index > 0) cookies[part.slice(0, index).trim()] = part.slice(index + 1).trim();
  }
  return cookies;
}

// The signed-in agent named by the sid cookie, or null.
function currentUser(req) {
  const sid = parseCookies(req).sid;
  const user = Object.prototype.hasOwnProperty.call(USERS, sid) ? USERS[sid] : null;
  return user ? { username: sid, name: user.name } : null;
}

function serveStatic(res, name) {
  const file = path.join(PUBLIC_DIR, path.basename(name));
  fs.readFile(file, (error, data) => {
    if (error) return notFound(res);
    send(res, 200, (MIME[path.extname(file)] || "application/octet-stream") + "; charset=utf-8", data);
  });
}

function notFound(res) {
  sendHtml(res, "error", 404, "Not found",
    "<h1>Page not found</h1><p>There is nothing at this address.</p><p><a href=\"/\">Back to the search</a></p>");
}

function signInRequired(res, next) {
  sendHtml(res, "error", 401, "Sign in required",
    "<h1>Sign in required</h1><p>This page is only available to signed-in agents.</p>" +
    '<p><a href="/login?next=' + escapeHtml(next) + '">Sign in</a></p>');
}

function homePage(res, url) {
  const query = url.searchParams.get("q");
  const content = query !== null
    ? "<h1>Search the knowledge base</h1>" +
      '<form action="/" method="get"><input id="q" name="q" value="' + escapeHtml(query) + '">' +
      '<button type="submit">Search</button></form>' +
      '<p class="result">Results for <b>' + escapeHtml(query) + "</b></p>"
    : "<h1>Support portal</h1>" +
      '<form action="/" method="get"><input id="q" name="q" placeholder="Search the knowledge base">' +
      '<button type="submit">Search</button></form>' +
      "<p>Sign in to see your open tickets and the team dashboard.</p>";
  sendHtml(res, "search", 200, "Search", content);
}

function loginPage(res, url) {
  // A target that is not a path on this site is dropped, not carried into the form.
  const next = safeNext(url.searchParams.get("next")) || "/dashboard";
  const html = layout("login", "Sign in",
    "<h1>Sign in</h1>" +
    '<form method="post" action="/login?next=' + encodeURIComponent(next) + '">' +
    '<label>Username <input id="username" name="username" autocomplete="username"></label>' +
    '<label>Password <input id="password" name="password" type="password" autocomplete="current-password"></label>' +
    '<input type="hidden" name="next" value="' + escapeHtml(next) + '">' +
    '<button type="submit">Sign in</button></form>');
  send(res, 200, "text/html; charset=utf-8", html);
}

async function loginSubmit(req, res, url) {
  const form = new URLSearchParams(await readBody(req));
  const username = form.get("username");
  const user = Object.prototype.hasOwnProperty.call(USERS, username) ? USERS[username] : null;
  if (!user || user.password !== form.get("password")) {
    return sendHtml(res, "login", 401, "Sign in failed",
      '<h1>Sign in failed</h1><p class="error">Unknown user or wrong password.</p><p><a href="/login">Try again</a></p>');
  }
  const requested = form.get("next") || url.searchParams.get("next");
  // No target at all: the dashboard, as before the hardening.
  const next = requested ? safeNext(requested) : "/dashboard";
  if (next === null) {
    return sendHtml(res, "login", 400, "Bad request",
      '<h1>Bad request</h1><p class="error">The sign-in target has to be a path on this site.</p>' +
      '<p><a href="/login">Back to the sign-in page</a></p>');
  }
  res.setHeader("Set-Cookie",
    [SESSION_COOKIE.name + "=" + encodeURIComponent(username), SESSION_COOKIE.attributes, "Max-Age=" + SESSION_COOKIE.maxAge].join("; "));
  redirect(res, next);
}

function dashboardPage(req, res) {
  const user = currentUser(req);
  if (!user) return signInRequired(res, "/dashboard");
  sendHtml(res, "dashboard", 200, "Dashboard",
    "<h1>Dashboard</h1><p>Signed in as " + escapeHtml(user.name) + ".</p>" +
    '<section id="notes"><p>Loading the latest notes...</p></section>' +
    '<button type="button" id="refresh-notes">Refresh notes</button>');
}

function accountPage(req, res) {
  const user = currentUser(req);
  if (!user) return signInRequired(res, "/account");
  sendHtml(res, "account", 200, "Account",
    "<h1>Account</h1><p>Signed in as " + escapeHtml(user.name) + ".</p>" +
    "<p>Role: support agent. Desk: second floor, row 4.</p>" +
    '<p><a href="/dashboard">Back to the dashboard</a></p>');
}

function apiJson(res, status, payload) {
  send(res, status, "application/json; charset=utf-8", JSON.stringify(payload));
}

async function apiEcho(req, res, url) {
  let message;
  if (req.method === "POST") {
    const payload = JSON.parse(await readBody(req));
    message = String(payload.message);
  } else {
    message = url.searchParams.get("message") || "";
  }
  apiJson(res, 200, { message: message, length: message.length, received_at: new Date().toISOString() });
}

async function handle(req, res) {
  const url = new URL(req.url, "http://localhost");
  if (url.pathname === "/") return homePage(res, url);
  if (url.pathname === "/login") return req.method === "POST" ? loginSubmit(req, res, url) : loginPage(res, url);
  if (url.pathname === "/dashboard") return dashboardPage(req, res);
  if (url.pathname === "/account") return accountPage(req, res);
  if (url.pathname === "/api/echo") return apiEcho(req, res, url);
  if (url.pathname.startsWith("/static/")) return serveStatic(res, url.pathname.slice("/static/".length));
  return notFound(res);
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((error) => {
    if (res.headersSent) return res.end();
    sendHtml(res, "error", 500, "Error",
      "<h1>Something went wrong</h1><p>The request could not be processed. Try again.</p>");
  });
});

server.listen(PORT, () => {
  console.log("listening " + server.address().port);
});
