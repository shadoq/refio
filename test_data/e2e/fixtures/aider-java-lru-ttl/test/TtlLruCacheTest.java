import java.util.List;
import java.util.Optional;

public class TtlLruCacheTest {
    private static int run = 0;
    private static int failures = 0;

    interface Body {
        void run() throws Exception;
    }

    static void test(String name, Body body) {
        run++;
        try {
            body.run();
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL " + name + ": " + t);
        }
    }

    static void eq(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void throwsA(Class<? extends Throwable> type, Body body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (!type.isInstance(t)) throw new AssertionError("expected " + type.getSimpleName() + " but got " + t);
            return;
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }

    static long[] now = {0};

    static TtlLruCache<String, Integer> cache(int capacity, long ttl) {
        now[0] = 0;
        return new TtlLruCache<>(capacity, ttl, () -> now[0]);
    }

    public static void main(String[] args) {
        test("stores and returns values", () -> {
            TtlLruCache<String, Integer> c = cache(3, 100);
            c.put("a", 1);
            eq(Optional.of(1), c.get("a"));
            eq(Optional.empty(), c.get("missing"));
            eq(1, c.size());
        });

        test("constructor and put validate their arguments", () -> {
            throwsA(IllegalArgumentException.class, () -> new TtlLruCache<String, Integer>(0, 10, () -> 0));
            throwsA(IllegalArgumentException.class, () -> new TtlLruCache<String, Integer>(1, 0, () -> 0));
            throwsA(NullPointerException.class, () -> new TtlLruCache<String, Integer>(1, 10, null));
            TtlLruCache<String, Integer> c = cache(2, 10);
            throwsA(IllegalArgumentException.class, () -> c.put("a", 1, 0));
            throwsA(IllegalArgumentException.class, () -> c.put("a", 1, -5));
            throwsA(NullPointerException.class, () -> c.put(null, 1));
            throwsA(NullPointerException.class, () -> c.put("a", null));
            eq(0, c.size());
        });

        test("an entry is gone exactly at stored time plus ttl", () -> {
            TtlLruCache<String, Integer> c = cache(3, 5);
            c.put("a", 1);
            now[0] = 4;
            eq(Optional.of(1), c.get("a"));
            now[0] = 5;
            eq(Optional.empty(), c.get("a"));
            eq(false, c.containsKey("a"));
            eq(0, c.size());
        });

        test("get does not extend the ttl", () -> {
            TtlLruCache<String, Integer> c = cache(3, 10);
            c.put("a", 1);
            now[0] = 9;
            eq(Optional.of(1), c.get("a"));
            now[0] = 10;
            eq(Optional.empty(), c.get("a"));
        });

        test("a per-entry ttl overrides the default", () -> {
            TtlLruCache<String, Integer> c = cache(3, 100);
            c.put("short", 1, 5);
            c.put("default", 2);
            c.put("long", 3, 500);
            now[0] = 6;
            eq(Optional.empty(), c.get("short"));
            eq(Optional.of(2), c.get("default"));
            now[0] = 100;
            eq(Optional.empty(), c.get("default"));
            eq(Optional.of(3), c.get("long"));
        });

        test("putting a live key replaces value and ttl", () -> {
            TtlLruCache<String, Integer> c = cache(3, 100);
            c.put("a", 1, 5);
            now[0] = 4;
            c.put("a", 2);
            now[0] = 6;
            eq(Optional.of(2), c.get("a"));
            eq(1, c.size());
        });

        test("putting an expired key stores it as a new entry", () -> {
            TtlLruCache<String, Integer> c = cache(2, 5);
            c.put("a", 1);
            c.put("b", 2);
            now[0] = 5;
            c.put("a", 3);
            eq(List.of("a"), c.keys());
            eq(Optional.of(3), c.get("a"));
        });

        test("the least recently used live entry is evicted when full", () -> {
            TtlLruCache<String, Integer> c = cache(2, 100);
            c.put("a", 1);
            c.put("b", 2);
            c.put("c", 3);
            eq(List.of("b", "c"), c.keys());
            eq(Optional.empty(), c.get("a"));
        });

        test("get makes an entry the most recently used", () -> {
            TtlLruCache<String, Integer> c = cache(2, 100);
            c.put("a", 1);
            c.put("b", 2);
            c.get("a");
            c.put("c", 3);
            eq(List.of("a", "c"), c.keys());
        });

        test("a miss does not change the order", () -> {
            TtlLruCache<String, Integer> c = cache(2, 100);
            c.put("a", 1);
            c.put("b", 2);
            c.get("zzz");
            eq(List.of("a", "b"), c.keys());
        });

        test("containsKey, remove, size and keys do not refresh recency", () -> {
            TtlLruCache<String, Integer> c = cache(2, 100);
            c.put("a", 1);
            c.put("b", 2);
            eq(true, c.containsKey("a"));
            c.size();
            c.keys();
            c.put("c", 3);
            eq(List.of("b", "c"), c.keys());
        });

        test("putting a live key at full capacity evicts nothing and refreshes it", () -> {
            TtlLruCache<String, Integer> c = cache(2, 100);
            c.put("a", 1);
            c.put("b", 2);
            c.put("a", 9);
            eq(List.of("b", "a"), c.keys());
            eq(2, c.size());
            eq(Optional.of(9), c.get("a"));
        });

        test("an expired entry is evicted before a live least recently used one", () -> {
            TtlLruCache<String, Integer> c = cache(2, 100);
            c.put("a", 1);
            c.put("b", 2, 5);
            now[0] = 6;
            c.put("c", 3);
            eq(List.of("a", "c"), c.keys());
            eq(Optional.of(1), c.get("a"));
        });

        test("an expired entry goes first even if it was just used", () -> {
            TtlLruCache<String, Integer> c = cache(2, 100);
            c.put("a", 1, 5);
            c.put("b", 2);
            now[0] = 1;
            c.get("a");
            now[0] = 6;
            c.put("c", 3);
            eq(List.of("b", "c"), c.keys());
        });

        test("reading an entry refreshes recency but not its ttl", () -> {
            TtlLruCache<String, Integer> c = cache(2, 100);
            c.put("a", 1, 10);
            c.put("b", 2);
            now[0] = 9;
            eq(Optional.of(1), c.get("a"));
            now[0] = 10;
            c.put("c", 3);
            eq(List.of("b", "c"), c.keys());
        });

        test("when everything has expired a new entry evicts nobody live", () -> {
            TtlLruCache<String, Integer> c = cache(2, 5);
            c.put("a", 1);
            c.put("b", 2);
            now[0] = 5;
            c.put("c", 3);
            c.put("d", 4);
            eq(List.of("c", "d"), c.keys());
            eq(2, c.size());
        });

        test("size and keys count only live entries", () -> {
            TtlLruCache<String, Integer> c = cache(5, 100);
            c.put("a", 1, 3);
            c.put("b", 2, 6);
            c.put("c", 3, 9);
            now[0] = 6;
            eq(1, c.size());
            eq(List.of("c"), c.keys());
        });

        test("remove reports only live entries", () -> {
            TtlLruCache<String, Integer> c = cache(3, 5);
            c.put("a", 1);
            c.put("b", 2, 100);
            eq(true, c.remove("b"));
            eq(false, c.remove("b"));
            eq(false, c.remove("never"));
            now[0] = 5;
            eq(false, c.remove("a"));
            eq(0, c.size());
        });

        test("capacity one keeps only the latest entry", () -> {
            TtlLruCache<String, Integer> c = cache(1, 100);
            c.put("a", 1);
            c.put("b", 2);
            eq(List.of("b"), c.keys());
            c.put("b", 3);
            eq(Optional.of(3), c.get("b"));
        });

        test("a huge ttl never overflows", () -> {
            TtlLruCache<String, Integer> c = cache(2, 100);
            now[0] = 1000;
            c.put("a", 1, Long.MAX_VALUE);
            now[0] = Long.MAX_VALUE - 1;
            eq(Optional.of(1), c.get("a"));
        });

        test("many operations keep the invariants", () -> {
            TtlLruCache<Integer, Integer> c = new TtlLruCache<>(50, 20, () -> now[0]);
            now[0] = 0;
            for (int i = 0; i < 2000; i++) {
                now[0] = i / 3;
                c.put(i % 70, i);
                if (i % 5 == 0) c.get((i * 7) % 70);
                check(c.size() <= 50, "capacity exceeded at step " + i);
                check(c.keys().size() == c.size(), "keys and size disagree at step " + i);
            }
            now[0] = 100000;
            eq(0, c.size());
        });

        System.out.println(run + " tests, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
