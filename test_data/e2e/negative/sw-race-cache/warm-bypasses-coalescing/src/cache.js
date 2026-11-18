'use strict';

/**
 * Request cache for the profile service.
 *
 * Fronts an async `loader(key)` with a TTL cache so the dashboard does not
 * hammer the upstream API. The dashboard renders several widgets per key in
 * parallel, so concurrent misses are expected to share one load. Rejected
 * loads must not be cached - the next caller retries.
 */
class RequestCache {
  /**
   * @param {(key: string) => Promise<*>} loader upstream read-through function
   * @param {{ ttlMs?: number }} [options] entry lifetime in milliseconds
   */
  constructor(loader, options = {}) {
    this.loader = loader;
    this.ttlMs = options.ttlMs || 60000;
    /** @type {Map<string, {value: *, storedAt: number}>} resolved entries */
    this.cache = new Map();
    /** @type {Map<string, Promise<*>>} in-flight loads (used by warm()) */
    this.pending = new Map();
    this.loads = 0; // exposed through stats() for the metrics endpoint
  }

  /**
   * Get a value through the cache. Concurrent callers for the same key are
   * expected to observe a single underlying load and the same value object.
   */
  async get(key) {
    if (this.cache.has(key)) {
      const hit = this.cache.get(key);
      if (Date.now() - hit.storedAt < this.ttlMs) {
        return hit.value;
      }
      this.cache.delete(key);
    }
    return this._load(key);
  }

  _load(key) {
    const existing = this.pending.get(key);
    if (existing) return existing;
    const promise = (async () => {
      try {
        await null; // ensure `promise` is assigned even if loader throws synchronously
        const value = await this.loader(key);
        if (this.pending.get(key) === promise) {
          this.loads += 1;
          this.cache.set(key, { value, storedAt: Date.now() });
        }
        return value;
      } finally {
        if (this.pending.get(key) === promise) this.pending.delete(key);
      }
    })();
    this.pending.set(key, promise);
    return promise;
  }

  /**
   * Pre-populate the cache for a batch of keys (nightly warmup job).
   * Duplicate keys in the list are legal and are expected to share a load.
   */
  async warm(keys) {
    const values = await Promise.all(
      keys.map(async (key) => {
        const value = await this.loader(key);
        this.loads += 1;
        this.cache.set(key, { value, storedAt: Date.now() });
        return value;
      })
    );
    return values;
  }

  /** Synchronous peek at a resolved entry; undefined when not cached. */
  peek(key) {
    const hit = this.cache.get(key);
    return hit ? hit.value : undefined;
  }

  /** Drop a key from the cache (used by the invalidation webhook). */
  drop(key) {
    this.pending.delete(key);
    return this.cache.delete(key);
  }

  /** Snapshot for the metrics endpoint. */
  stats() {
    return {
      entries: this.cache.size,
      inflight: this.pending.size,
      loads: this.loads,
    };
  }
}

module.exports = { RequestCache };
