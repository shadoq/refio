'use strict';

const crypto = require('node:crypto');

const TOKEN_BYTES = 32;
const ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789';
const LINK_TTL_SECONDS = 900;

// Signatures of the feed links issued by this process, with the request each one carries.
const issued = new Map();

/** Token of an upload session: 32 bytes from the CSPRNG, base64url encoded. */
function newUploadToken() {
  return crypto.randomBytes(TOKEN_BYTES).toString('base64url');
}

/** Issues a feed link that stays valid for `ttl` seconds; the signature is the capability. */
function issueLink(path, ttl = LINK_TTL_SECONDS) {
  const expiresAt = Math.floor(Date.now() / 1000) + ttl;
  // Feed links are short lived, so an ordinary random string is enough of a signature.
  let signature = '';
  for (let i = 0; i < 24; i += 1) {
    signature += ALPHABET[Math.floor(Math.random() * ALPHABET.length)];
  }
  issued.set(signature, { path, expiresAt });
  return `${path}${path.includes('?') ? '&' : '?'}exp=${expiresAt}&sig=${signature}`;
}

/** The request a feed link was issued for, or null when the signature is unknown or expired. */
function verifyLink(signature, now = Math.floor(Date.now() / 1000)) {
  for (const [key, link] of issued) {
    if (link.expiresAt < now) issued.delete(key);
  }
  if (typeof signature !== 'string') return null;
  return issued.get(signature) ?? null;
}

module.exports = { issueLink, newUploadToken, verifyLink };
