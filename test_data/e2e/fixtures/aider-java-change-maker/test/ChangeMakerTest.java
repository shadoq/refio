import java.util.List;

public class ChangeMakerTest {
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

    static void change(int amount, List<Integer> coins, List<Integer> expected) {
        test("change " + amount + " with " + coins, () -> eq(expected, ChangeMaker.change(amount, coins)));
    }

    static void fails(int amount, List<Integer> coins, String message) {
        test("error for " + amount + " with " + coins, () -> {
            try {
                ChangeMaker.change(amount, coins);
            } catch (IllegalArgumentException e) {
                eq(message, e.getMessage());
                return;
            }
            throw new AssertionError("expected IllegalArgumentException");
        });
    }

    public static void main(String[] args) {
        change(15, List.of(1, 5, 10, 25), List.of(5, 10));
        change(63, List.of(1, 5, 10, 21, 25), List.of(21, 21, 21));
        change(6, List.of(1, 2, 3, 4, 5), List.of(1, 5));
        change(8, List.of(1, 4, 5), List.of(4, 4));
        change(24, List.of(1, 5, 10, 12, 25), List.of(12, 12));
        change(10, List.of(5, 2), List.of(5, 5));
        change(9, List.of(3, 4, 5, 6), List.of(3, 6));
        change(100, List.of(1, 5, 10, 25, 50), List.of(50, 50));
        change(11, List.of(9, 6, 1), List.of(1, 1, 9));
        change(20, List.of(1, 5, 10, 25, 4), List.of(10, 10));
        change(12, List.of(6, 3, 2, 1, 6, 3), List.of(6, 6));
        change(47, List.of(1, 2, 4, 5, 11, 13), List.of(1, 11, 11, 11, 13));
        change(57, List.of(3, 4, 7, 8, 13, 14), List.of(3, 13, 13, 14, 14));
        change(19, List.of(1, 3, 9, 13), List.of(1, 9, 9));
        change(49, List.of(1, 2, 3, 4, 11, 14), List.of(2, 11, 11, 11, 14));

        change(0, List.of(1, 5), List.of());
        change(0, List.of(), List.of());

        fails(-1, List.of(1, 5), "Negative totals are not allowed.");
        fails(-1, List.of(0), "Negative totals are not allowed.");
        fails(5, List.of(1, 0, 5), "Denominations must be positive.");
        fails(0, List.of(-3, 1), "Denominations must be positive.");
        fails(7, List.of(2, 4), "The total 7 cannot be represented in the given currency.");
        fails(3, List.of(), "The total 3 cannot be represented in the given currency.");
        fails(1, List.of(2, 5), "The total 1 cannot be represented in the given currency.");
        fails(14, List.of(5, 10), "The total 14 cannot be represented in the given currency.");

        test("a large amount is fast and uses the fewest coins", () -> {
            long started = System.nanoTime();
            List<Integer> coins = ChangeMaker.change(99999, List.of(101, 7, 29, 13));
            long millis = (System.nanoTime() - started) / 1_000_000;
            int sum = 0;
            for (int c : coins) sum += c;
            eq(99999, sum);
            eq(995, coins.size());
            for (int i = 1; i < coins.size(); i++) check(coins.get(i - 1) <= coins.get(i), "not sorted");
            check(millis < 5000, "too slow: " + millis + " ms");
        });

        test("an unmodifiable input list is not modified", () -> {
            List<Integer> coins = List.of(25, 1, 10, 5);
            eq(List.of(5, 10), ChangeMaker.change(15, coins));
            eq(List.of(25, 1, 10, 5), coins);
        });

        System.out.println(run + " tests, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
