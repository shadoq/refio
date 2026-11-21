import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

public class TtlLruCache<K, V> {
    private static final class Entry<V> {
        final V value;
        final long ttl;
        final long expiresAt;

        Entry(V value, long ttl, long expiresAt) {
            this.value = value;
            this.ttl = ttl;
            this.expiresAt = expiresAt;
        }
    }

    private final int capacity;
    private final long defaultTtl;
    private final LongSupplier clock;
    // eldest first: iteration order is least recently used to most recently used
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>();

    public TtlLruCache(int capacity, long defaultTtlMillis, LongSupplier clock) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (defaultTtlMillis <= 0) throw new IllegalArgumentException("ttl must be positive");
        this.capacity = capacity;
        this.defaultTtl = defaultTtlMillis;
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized void put(K key, V value) {
        put(key, value, defaultTtl);
    }

    public synchronized void put(K key, V value, long ttlMillis) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        if (ttlMillis <= 0) throw new IllegalArgumentException("ttl must be positive");
        long now = clock.getAsLong();
        purge(now);
        entries.remove(key);
        if (entries.size() >= capacity) {
            Iterator<K> eldest = entries.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        long expiresAt = now > Long.MAX_VALUE - ttlMillis ? Long.MAX_VALUE : now + ttlMillis;
        entries.put(key, new Entry<>(value, ttlMillis, expiresAt));
    }

    public synchronized Optional<V> get(K key) {
        Objects.requireNonNull(key);
        long now = clock.getAsLong();
        Entry<V> entry = entries.get(key);
        if (entry == null) return Optional.empty();
        if (isExpired(entry, now)) {
            entries.remove(key);
            return Optional.empty();
        }
        entries.remove(key);
        entries.put(key, new Entry<>(entry.value, entry.ttl, now + entry.ttl));
        return Optional.of(entry.value);
    }

    public synchronized boolean containsKey(K key) {
        Objects.requireNonNull(key);
        long now = clock.getAsLong();
        Entry<V> entry = entries.get(key);
        if (entry == null) return false;
        if (isExpired(entry, now)) {
            entries.remove(key);
            return false;
        }
        return true;
    }

    public synchronized boolean remove(K key) {
        Objects.requireNonNull(key);
        long now = clock.getAsLong();
        Entry<V> entry = entries.remove(key);
        return entry != null && !isExpired(entry, now);
    }

    public synchronized int size() {
        purge(clock.getAsLong());
        return entries.size();
    }

    public synchronized List<K> keys() {
        purge(clock.getAsLong());
        return new ArrayList<>(entries.keySet());
    }

    private void purge(long now) {
        Iterator<Map.Entry<K, Entry<V>>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (isExpired(it.next().getValue(), now)) it.remove();
        }
    }

    private static boolean isExpired(Entry<?> entry, long now) {
        return now >= entry.expiresAt;
    }
}
