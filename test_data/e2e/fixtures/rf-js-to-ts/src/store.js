/**
 * store.js - tiny in-memory key/value store with per-key TTL.
 *
 * The API gateway caches upstream responses here between requests. The
 * store is untyped: any value can be cached under a string key. Entries
 * carry an optional expiry: reads past the expiry behave as misses, the
 * periodic sweep() drops them for real, and set() evicts the oldest entry
 * once the configured bound is reached.
 */
"use strict";

var DEFAULT_TTL_MS = 30000;
var MAX_ENTRIES = 1000;

/**
 * Build a store.
 *
 * @param {Object} [options]
 * @param {number} [options.defaultTtlMs] TTL used when set() gets none
 * @param {number} [options.maxEntries] eviction bound for set()
 * @param {function():number} [options.now] clock override for tests
 * @constructor
 */
function Store(options) {
  options = options || {};
  this.defaultTtlMs =
    options.defaultTtlMs === undefined ? DEFAULT_TTL_MS : options.defaultTtlMs;
  this.maxEntries = options.maxEntries === undefined ? MAX_ENTRIES : options.maxEntries;
  this.now = options.now || function () { return Date.now(); };
  this.entries = {}; // key -> { value, expiresAt, storedAt }
  this.order = [];   // live keys in insertion order, oldest first
}

/** Store a value under key, evicting the oldest entry past the bound. */
Store.prototype.set = function (key, value, ttlMs) {
  var ttl = ttlMs === undefined ? this.defaultTtlMs : ttlMs;
  var storedAt = this.now();
  if (!Object.prototype.hasOwnProperty.call(this.entries, key)) {
    this.order.push(key);
  } else {
    // refresh position: re-inserting moves the key to the newest end
    this.order.splice(this.order.indexOf(key), 1);
    this.order.push(key);
  }
  this.entries[key] = {
    value: value,
    expiresAt: ttl === null ? null : storedAt + ttl,
    storedAt: storedAt
  };
  while (this.order.length > this.maxEntries) {
    this.delete(this.order[0]);
  }
  return this;
};

/** Read a value; expired entries behave as misses. */
Store.prototype.get = function (key) {
  var entry = this.liveEntry(key);
  return entry ? entry.value : undefined;
};

/** True when the key exists and has not expired. */
Store.prototype.has = function (key) {
  return this.liveEntry(key) !== null;
};

/** Drop a key; true when something was dropped. */
Store.prototype.delete = function (key) {
  if (!Object.prototype.hasOwnProperty.call(this.entries, key)) {
    return false;
  }
  delete this.entries[key];
  var at = this.order.indexOf(key);
  if (at !== -1) {
    this.order.splice(at, 1);
  }
  return true;
};

/** Live keys in insertion order. */
Store.prototype.keys = function () {
  this.sweep();
  return this.order.slice();
};

/** Number of live entries. */
Store.prototype.size = function () {
  this.sweep();
  return this.order.length;
};

/**
 * Remaining TTL in ms, null for entries that never expire and undefined on
 * a miss.
 */
Store.prototype.ttl = function (key) {
  var entry = this.liveEntry(key);
  if (entry === null) {
    return undefined;
  }
  return entry.expiresAt === null ? null : entry.expiresAt - this.now();
};

/** Drop every expired entry; returns how many went. */
Store.prototype.sweep = function () {
  var dropped = 0;
  var keys = this.order.slice();
  for (var i = 0; i < keys.length; i++) {
    var entry = this.entries[keys[i]];
    if (entry && entry.expiresAt !== null && entry.expiresAt <= this.now()) {
      this.delete(keys[i]);
      dropped += 1;
    }
  }
  return dropped;
};

/** Forget everything. */
Store.prototype.clear = function () {
  this.entries = {};
  this.order = [];
};

/** The entry when live, else null. */
Store.prototype.liveEntry = function (key) {
  var entry = this.entries[key];
  if (!entry) {
    return null;
  }
  if (entry.expiresAt !== null && entry.expiresAt <= this.now()) {
    return null;
  }
  return entry;
};

module.exports = Store;
