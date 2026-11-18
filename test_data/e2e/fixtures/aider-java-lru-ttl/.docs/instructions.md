# Instructions

Implement `TtlLruCache<K, V>` in `src/TtlLruCache.java`: a cache with a fixed capacity, least-recently-used eviction and a time to live for every entry. It never reads the system clock; time comes from a `LongSupplier` (milliseconds) given to the constructor, so tests can drive it by hand.

## Constructor

`TtlLruCache(int capacity, long defaultTtlMillis, LongSupplier clock)`

`capacity` and `defaultTtlMillis` must be greater than 0 (`IllegalArgumentException`), and `clock` must not be null (`NullPointerException`).

## Methods

- `void put(K key, V value)` stores the entry with the default TTL. `void put(K key, V value, long ttlMillis)` uses its own TTL, which must be greater than 0 (`IllegalArgumentException`). Null keys or values throw `NullPointerException`.
- `Optional<V> get(K key)` returns the value of a live entry, or an empty optional.
- `boolean containsKey(K key)` tells whether a live entry exists.
- `boolean remove(K key)` removes a live entry and returns `true`; it returns `false` when there is no live entry for the key.
- `int size()` is the number of live entries.
- `List<K> keys()` lists the keys of the live entries from the least recently used to the most recently used.

## Time to live

An entry stored at time `t` with TTL `d` is live while `now < t + d` and expired from `t + d` on, so at exactly `t + d` it is already gone. An expired entry is invisible to every method: it is not returned, not counted, not listed, and `remove` does not report it. A TTL so large that `t + d` would overflow `long` means the entry never expires.

`get` does **not** extend the TTL: an entry lives for the TTL it was stored with, however often it is read.

## Recency and eviction

- `put` and a successful `get` make the entry the most recently used. `containsKey`, `size`, `keys` and `remove` do not change the order.
- Putting a key that is already live replaces its value and its TTL (the new TTL counts from now) and makes it the most recently used. It never evicts anything.
- Before a new key is stored, all expired entries are dropped. Only if the cache is still full, the least recently used live entry is evicted. So an expired entry, even one that was used a moment ago, always goes before a live entry, and a live entry is never evicted while an expired one still takes space.
