public class ColumnLabelTest {
    private static int failures = 0;

    private interface Body {
        void run() throws Exception;
    }

    private static void test(String name, Body body) {
        try {
            body.run();
            System.out.println("ok   " + name);
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL " + name + ": " + t);
        }
    }

    private static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void rejects(Body body) {
        try {
            body.run();
        } catch (IllegalArgumentException e) {
            return;
        } catch (Exception e) {
            throw new AssertionError("wrong exception " + e);
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    public static void main(String[] args) {
        test("single letters", () -> {
            eq("A", ColumnLabel.toLabel(1));
            eq("B", ColumnLabel.toLabel(2));
            eq("Y", ColumnLabel.toLabel(25));
        });
        test("26 is Z", () -> eq("Z", ColumnLabel.toLabel(26)));
        test("27 and 28 start the second letter", () -> {
            eq("AA", ColumnLabel.toLabel(27));
            eq("AB", ColumnLabel.toLabel(28));
            eq("AY", ColumnLabel.toLabel(51));
        });
        test("52 is AZ and 53 is BA", () -> {
            eq("AZ", ColumnLabel.toLabel(52));
            eq("BA", ColumnLabel.toLabel(53));
            eq("BZ", ColumnLabel.toLabel(78));
            eq("ZZ", ColumnLabel.toLabel(702));
        });
        test("three letters", () -> {
            eq("AAA", ColumnLabel.toLabel(703));
            eq("ZZZ", ColumnLabel.toLabel(18278));
            eq("AAAA", ColumnLabel.toLabel(18279));
        });
        test("largest int", () -> eq("FXSHRXW", ColumnLabel.toLabel(Integer.MAX_VALUE)));
        test("columns below one are rejected", () -> {
            rejects(() -> ColumnLabel.toLabel(0));
            rejects(() -> ColumnLabel.toLabel(-5));
        });
        test("fromLabel reads labels", () -> {
            eq(1, ColumnLabel.fromLabel("A"));
            eq(26, ColumnLabel.fromLabel("Z"));
            eq(27, ColumnLabel.fromLabel("AA"));
            eq(52, ColumnLabel.fromLabel("AZ"));
            eq(702, ColumnLabel.fromLabel("ZZ"));
            eq(703, ColumnLabel.fromLabel("AAA"));
        });
        test("fromLabel ignores case", () -> {
            eq(28, ColumnLabel.fromLabel("ab"));
            eq(53, ColumnLabel.fromLabel("bA"));
        });
        test("fromLabel accepts the largest int and rejects the next column", () -> {
            eq(Integer.MAX_VALUE, ColumnLabel.fromLabel("FXSHRXW"));
            rejects(() -> ColumnLabel.fromLabel("FXSHRXX"));
            rejects(() -> ColumnLabel.fromLabel("AAAAAAAAAAAA"));
            rejects(() -> ColumnLabel.fromLabel("ZZZZZZZZZZZZZZZZZZZZZZ"));
        });
        test("fromLabel rejects bad labels", () -> {
            rejects(() -> ColumnLabel.fromLabel(null));
            rejects(() -> ColumnLabel.fromLabel(""));
            rejects(() -> ColumnLabel.fromLabel("A1"));
            rejects(() -> ColumnLabel.fromLabel(" A"));
            rejects(() -> ColumnLabel.fromLabel("A-B"));
        });
        test("round trip", () -> {
            int[] samples = {1, 25, 26, 27, 51, 52, 53, 676, 677, 702, 703, 1000, 16384, 18278, 18279, 1000000, Integer.MAX_VALUE};
            for (int n : samples) {
                eq(n, ColumnLabel.fromLabel(ColumnLabel.toLabel(n)));
            }
            for (int n = 1; n <= 20000; n++) {
                if (n != ColumnLabel.fromLabel(ColumnLabel.toLabel(n))) {
                    throw new AssertionError("round trip failed for " + n);
                }
            }
        });
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
