'use strict';

const config = require('./config');
const { applyParams, mergeLimits, PRESETS } = require('./options');
const { fetchByUrl, fetchFromOrigin } = require('./source');
const { isSize, parseDimensions } = require('./validate');
const { issueLink, newUploadToken, verifyLink } = require('./signing');
const { readOriginal } = require('./store');

const CDN_ORIGIN = 'https://images.acme.example';

function reply(status, type, body) {
  return { status, type, body };
}

const text = (status, message) => reply(status, 'text/plain', `${message}\n`);
const json = (status, value) => reply(status, 'application/json', `${JSON.stringify(value)}\n`);
const badRequest = (message) => text(400, message);

function presetNamed(name) {
  return Object.hasOwn(PRESETS, name) ? PRESETS[name] : PRESETS.preview;
}

/** The largest size with the aspect ratio of `size` that fits into the limits. */
function fit(size, options) {
  const scale = Math.min(options.maxWidth / size.width, options.maxHeight / size.height, 1);
  return {
    width: Math.max(1, Math.round(size.width * scale)),
    height: Math.max(1, Math.round(size.height * scale)),
  };
}

/** What the encoders receive: the size of the source, the target size and the output settings. */
function job(source, size, options) {
  return json(200, { sourceBytes: source.length, ...fit(size, options), format: options.format });
}

/** `GET /resize?src=<url>&size=<WxH>&preset=<name>`: an original from a partner feed. */
async function resize(query) {
  const size = parseDimensions(query.get('size'));
  if (!size) return badRequest('size must be WxH, for example 640x480');
  const options = mergeLimits(presetNamed(query.get('preset')), Object.fromEntries(query));
  const source = await fetchByUrl(query.get('src'));
  return job(source, size, options);
}

/** `GET /resize/cdn?path=<path>&size=<WxH>`: an original from the CDN layer. */
async function resizeFromCdn(query) {
  const size = parseDimensions(query.get('size'));
  if (!size) return badRequest('size must be WxH, for example 640x480');
  const source = await fetchFromOrigin(new URL(query.get('path') ?? '/', CDN_ORIGIN));
  return job(source, size, PRESETS.preview);
}

/** `POST /resize?preset=<name>`: shows the options a call with this JSON body would run with. */
function previewOptions(query, body) {
  let params;
  try {
    params = JSON.parse(body);
  } catch (error) {
    return badRequest('options must be a JSON object');
  }
  if (params === null || typeof params !== 'object' || Array.isArray(params)) {
    return badRequest('options must be a JSON object');
  }
  return json(200, { options: applyParams(presetNamed(query.get('preset')), params) });
}

/** `GET /signed?src=<url>&size=<WxH>`: a link that carries the request and expires by itself. */
function signRequest(query) {
  if (!isSize(query.get('size'))) {
    return badRequest('size must be WxH, optionally with a density suffix such as @2x');
  }
  const target = `/s?src=${encodeURIComponent(query.get('src') ?? '')}&size=${encodeURIComponent(query.get('size'))}`;
  return json(200, { link: issueLink(target) });
}

/** `GET /s?sig=<signature>`: the request a signed link was issued for. */
async function signedRequest(query) {
  const link = verifyLink(query.get('sig'));
  if (!link) return text(403, 'unknown or expired link');
  return resize(new URL(link.path, 'http://localhost').searchParams);
}

/** `GET /originals?name=<file>`: a stored original, by its file name. */
async function original(query) {
  return reply(200, 'application/octet-stream', await readOriginal(query.get('name')));
}

const ROUTES = [
  { method: 'GET', path: '/health',
    run: () => json(200, { service: 'image-resizer', version: config.version, status: 'ok' }) },
  { method: 'GET', path: '/resize', run: (query) => resize(query) },
  { method: 'GET', path: '/resize/cdn', run: (query) => resizeFromCdn(query) },
  { method: 'POST', path: '/resize', run: (query, body) => previewOptions(query, body) },
  { method: 'GET', path: '/signed', run: (query) => signRequest(query) },
  { method: 'GET', path: '/s', run: (query) => signedRequest(query) },
  { method: 'GET', path: '/originals', run: (query) => original(query) },
  { method: 'GET', path: '/upload-token', run: () => json(200, { token: newUploadToken() }) },
];

async function handle({ method, url, body }) {
  const route = ROUTES.find((candidate) => candidate.path === url.pathname);
  if (!route) return text(404, 'no such route');
  if (route.method !== method) return text(405, 'method not allowed');
  return route.run(url.searchParams, body);
}

module.exports = { handle };
