// headers-app: the support team's status portal.
//
// Start it with `node server.js` (PORT environment variable, default 3000). On start-up it
// prints `listening <port>` so that smoke scripts can find the port; PORT=0 asks the OS for a
// free one.
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

function layout(title, content) {
  return [
    "<!doctype html>",
    '<html lang="en">',
    "<head>",
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    "<title>" + title + " - Support portal</title>",
    '<link rel="stylesheet" href="/static/style.css">',
    "</head>",
    "<body>",
    '<header><a class="brand" href="/">Support portal</a>' +
      '<nav><a href="/">Search</a><a href="/dashboard">Dashboard</a><a href="/account">Account</a></nav></header>',
    "<main>",
    content,
    "</main>",
    "</body>",
    "</html>",
  ].join("\n");
}

function sendHtml(res, status, html) {
  res.writeHead(status, { "Content-Type": "text/html; charset=utf-8" });
  res.end(html);
}

function redirect(res, location) {
  res.writeHead(302, { Location: location });
  res.end();
}

function notFound(res) {
  sendHtml(res, 404, layout("Not found",
    "<h1>Page not found</h1><p>There is nothing at this address.</p><p><a href=\"/\">Back to the search</a></p>"));
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

function serveStatic(res, name) {
  const file = path.join(PUBLIC_DIR, path.basename(name));
  fs.readFile(file, (error, data) => {
    if (error) return notFound(res);
    res.writeHead(200, { "Content-Type": MIME[path.extname(file)] || "application/octet-stream" });
    res.end(data);
  });
}

function homePage(res, url) {
  const query = url.searchParams.get("q");
  const searchForm = '<form action="/" method="get"><input id="q" name="q" placeholder="Search the knowledge base">' +
    '<button type="submit">Search</button></form>';
  const content = query !== null
    ? "<h1>Search the knowledge base</h1>" +
      '<form action="/" method="get"><input id="q" name="q" value="' + query + '">' +
      '<button type="submit">Search</button></form>' +
      '<p class="result">Results for <b>' + query + "</b></p>" +
      '<script>document.getElementById("q").focus();</script>'
    : "<h1>Support portal</h1>" + searchForm +
      "<p>Sign in to see your open tickets and the team dashboard.</p>" +
      '<script>document.getElementById("q").focus();</script>';
  sendHtml(res, 200, layout("Search", content));
}

function loginPage(res, url) {
  const next = url.searchParams.get("next") || "/dashboard";
  const html = layout("Sign in",
    "<h1>Sign in</h1>" +
    '<form method="post" action="/login?next=' + encodeURIComponent(next) + '">' +
    '<label>Username <input id="username" name="username" autocomplete="username"></label>' +
    '<label>Password <input id="password" name="password" type="password" autocomplete="current-password"></label>' +
    '<input type="hidden" name="next" value="' + next + '">' +
    '<button type="submit">Sign in</button></form>' +
    '<script>document.getElementById("username").focus();</script>');
  sendHtml(res, 200, html);
}

async function loginSubmit(req, res, url) {
  const form = new URLSearchParams(await readBody(req));
  const user = USERS[form.get("username")];
  if (!user || user.password !== form.get("password")) {
    return sendHtml(res, 401, layout("Sign in failed",
      '<h1>Sign in failed</h1><p class="error">Unknown user or wrong password.</p><p><a href="/login">Try again</a></p>'));
  }
  const next = form.get("next") || url.searchParams.get("next") || "/dashboard";
  res.setHeader("Set-Cookie", "sid=" + form.get("username") + "; Path=/");
  redirect(res, next);
}

function dashboardPage(req, res) {
  const cookies = parseCookies(req);
  const user = USERS[cookies.sid];
  if (!user) {
    return sendHtml(res, 401, layout("Sign in required",
      "<h1>Sign in required</h1><p>The dashboard is only available to signed-in agents.</p>" +
      '<p><a href="/login?next=/dashboard">Sign in</a></p>'));
  }
  sendHtml(res, 200, layout("Dashboard",
    "<h1>Dashboard</h1><p>Signed in as " + user.name + ".</p>" +
    '<section id="notes"><p>Loading the latest notes...</p></section>' +
    '<button type="button" onclick="loadNotes()">Refresh notes</button>' +
    '<script src="/static/app.js"></script>' +
    "<script>\n" +
    "function loadNotes() {\n" +
    '  var box = document.getElementById("notes");\n' +
    '  box.textContent = "Open tickets: 7. Escalated: 1.";\n' +
    '  SupportPortal.mark(box, "Escalated");\n' +
    "}\n" +
    "loadNotes();\n" +
    "</script>"));
}

function accountPage(req, res) {
  const cookies = parseCookies(req);
  const user = USERS[cookies.sid];
  if (!user) {
    return sendHtml(res, 401, layout("Sign in required",
      "<h1>Sign in required</h1><p>The account page is only available to signed-in agents.</p>" +
      '<p><a href="/login?next=/account">Sign in</a></p>'));
  }
  sendHtml(res, 200, layout("Account",
    "<h1>Account</h1><p>Signed in as " + user.name + ".</p>" +
    "<p>Role: support agent. Desk: second floor, row 4.</p>" +
    '<p><a href="/dashboard">Back to the dashboard</a></p>'));
}

async function apiEcho(req, res, url) {
  let message;
  if (req.method === "POST") {
    const payload = JSON.parse(await readBody(req));
    message = String(payload.message);
  } else {
    message = url.searchParams.get("message") || "";
  }
  const body = JSON.stringify({ message: message, length: message.length, received_at: new Date().toISOString() });
  res.writeHead(200, { "Content-Type": "application/json" });
  res.end(body);
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
    res.writeHead(500, { "Content-Type": "text/html; charset=utf-8" });
    res.end(layout("Error", "<h1>Something went wrong</h1><pre>" + error + "</pre>"));
  });
});

server.listen(PORT, () => {
  console.log("listening " + server.address().port);
});
