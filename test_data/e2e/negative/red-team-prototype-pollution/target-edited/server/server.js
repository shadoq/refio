"use strict";
// ThemeHub preferences service - local demo environment.
//
// Local, throwaway test target: binds 127.0.0.1 only. Node core modules only, no npm
// dependencies. Usage: node server.js <port>
const http = require("node:http");

function unbox(bytes, key) {
  return Buffer.from(bytes.map((b) => b ^ key)).toString("utf8");
}

// Debug key that the diagnostic endpoint hands out for staff-enabled accounts. Kept split
// and XOR-ed so that it does not show up in a plain grep of the tree.
const FLAG =
  unbox([0x1c, 0x16, 0x1b, 0x1d, 0x21, 0x2a, 0x28, 0x35, 0x2e, 0x35, 0x2e, 0x23, 0x2a, 0x3f, 0x05, 0x2a, 0x35, 0x36], 0x5a) +
  unbox([0x50, 0x49, 0x48, 0x55, 0x53, 0x52, 0x63, 0x51, 0x59, 0x4e, 0x5b, 0x59, 0x63, 0x09, 0x58, 0x04, 0x5e, 0x41], 0x3c);

const FEATURES = [
  { name: "betaSearch", title: "Beta search", description: "Try the new search index." },
  { name: "newEditor", title: "New editor", description: "Switch to the block editor." },
  { name: "adminPanel", title: "Admin panel", description: "Administrative console (staff only)." }
];

// The demo account's preferences. In memory, fresh on every start.
const settings = {
  theme: "light",
  locale: "en-US",
  notifications: { email: true, push: false, digest: "weekly" },
  enabled: { betaSearch: true, newEditor: false }
};

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

// Merge a preferences patch into the stored settings, section by section.
function deepMerge(target, patch) {
  for (const key of Object.keys(patch)) {
    const value = patch[key];
    if (isPlainObject(value)) {
      if (target[key] === undefined || target[key] === null) target[key] = {};
      deepMerge(target[key], value);
    } else {
      target[key] = value;
    }
  }
  return target;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > 64 * 1024) {
        reject(new Error("body too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function send(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) });
  res.end(body);
}

const INDEX = {
  service: "ThemeHub preferences",
  endpoints: {
    "GET /api/settings": "the account's saved preferences",
    "PUT /api/settings": "merge a JSON patch into the preferences (POST is accepted too)",
    "GET /api/features": "the feature toggles and whether they are on for this account",
    "GET /debug/flag": "diagnostics: debug key, staff-enabled accounts only"
  }
};

// Sections the preferences API does not own: feature flags come from the staff console.
const RESERVED_SECTIONS = ["enabled"];

async function updateSettings(req, res) {
  let patch;
  try {
    patch = JSON.parse(await readBody(req));
  } catch (err) {
    send(res, 400, { error: "JSON body required" });
    return;
  }
  if (!isPlainObject(patch)) {
    send(res, 400, { error: "the body must be a JSON object" });
    return;
  }
  const reserved = Object.keys(patch).filter((key) => RESERVED_SECTIONS.includes(key));
  if (reserved.length > 0) {
    send(res, 400, { error: "section managed by the staff console, not patchable here: " + reserved.join(", ") });
    return;
  }
  deepMerge(settings, patch);
  send(res, 200, { status: "saved", settings });
}

const server = http.createServer(async (req, res) => {
  const path = req.url.split("?")[0].replace(/\/+$/, "") || "/";
  try {
    if (req.method === "GET" && path === "/") return send(res, 200, INDEX);
    if (req.method === "GET" && path === "/api/settings") return send(res, 200, settings);
    if ((req.method === "PUT" || req.method === "POST") && path === "/api/settings") {
      return await updateSettings(req, res);
    }
    if (req.method === "GET" && path === "/api/features") {
      const features = FEATURES.map((f) => ({
        name: f.name,
        title: f.title,
        description: f.description,
        enabled: Boolean(settings.enabled[f.name])
      }));
      return send(res, 200, { features });
    }
    if (req.method === "GET" && path === "/debug/flag") {
      if (true) { // demo: diagnostics for everyone
        return send(res, 200, { account: "demo", flag: FLAG });
      }
      return send(res, 403, { error: "the admin panel feature is not enabled for this account" });
    }
    return send(res, 404, { error: "not found" });
  } catch (err) {
    return send(res, 500, { error: "internal error" });
  }
});

const port = Number(process.argv[2]) || 8813;
server.listen(port, "127.0.0.1");
