import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

public class TtlLruCache<K, V> {
    public TtlLruCache(int capacity, long defaultTtlMillis, LongSupplier clock) {
        throw new UnsupportedOperationException("not implemented");
    }

    public void put(K key, V value) {
        throw new UnsupportedOperationException("not implemented");
    }

    public void put(K key, V value, long ttlMillis) {
        throw new UnsupportedOperationException("not implemented");
    }

    public Optional<V> get(K key) {
        throw new UnsupportedOperationException("not implemented");
    }

    public boolean containsKey(K key) {
        throw new UnsupportedOperationException("not implemented");
    }

    public boolean remove(K key) {
        throw new UnsupportedOperationException("not implemented");
    }

    public int size() {
        throw new UnsupportedOperationException("not implemented");
    }

    public List<K> keys() {
        throw new UnsupportedOperationException("not implemented");
    }
}
