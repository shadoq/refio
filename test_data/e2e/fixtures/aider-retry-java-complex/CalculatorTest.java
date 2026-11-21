public class CalculatorTest {
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

    private static void eq(String expression, long expected) {
        long actual = Calculator.evaluate(expression);
        if (actual != expected) {
            throw new AssertionError("<" + expression + "> expected " + expected + " but was " + actual);
        }
    }

    private static void fails(String expression) {
        try {
            long value = Calculator.evaluate(expression);
            throw new AssertionError("<" + expression + "> should fail but gave " + value);
        } catch (CalcException expected) {
            // fine
        }
    }

    public static void main(String[] args) {
        test("literals and whitespace", () -> {
            eq("42", 42);
            eq("  7 ", 7);
            eq(" 1 +  2 ", 3);
            eq("0", 0);
        });
        test("precedence of * over +", () -> {
            eq("2+3*4", 14);
            eq("(2+3)*4", 20);
            eq("2*3+4*5", 26);
        });
        test("left associativity", () -> {
            eq("10-4-3", 3);
            eq("100/10/5", 2);
            eq("10%4*3", 6);
            eq("2*8/4", 4);
        });
        test("power is right associative", () -> {
            eq("2^3", 8);
            eq("2^3^2", 512);
            eq("2^2^3", 256);
            eq("(2^3)^2", 64);
        });
        test("unary minus binds looser than power", () -> {
            eq("-2^2", -4);
            eq("(-2)^2", 4);
            eq("-3^2", -9);
            eq("-(2+3)^2", -25);
            eq("2*-3^2", -18);
        });
        test("unary operators can repeat", () -> {
            eq("--3", 3);
            eq("-+-3", 3);
            eq("1 + + 2", 3);
            eq("2*-3", -6);
            eq("+5", 5);
        });
        test("division and remainder follow the sign rules", () -> {
            eq("7/2", 3);
            eq("-7/2", -3);
            eq("7/-2", -3);
            eq("7%3", 1);
            eq("-7%3", -1);
            eq("7%-3", 1);
        });
        test("division by zero is an error", () -> {
            fails("7/0");
            fails("7%0");
            fails("1/(2-2)");
        });
        test("powers of zero, one and minus one", () -> {
            eq("0^0", 1);
            eq("0^5", 0);
            eq("5^0", 1);
            eq("(-1)^3", -1);
            eq("(-1)^4", 1);
            eq("1^9223372036854775807", 1);
            eq("(0-1)^9223372036854775807", -1);
        });
        test("negative exponents are an error", () -> {
            fails("2^-1");
            fails("-2^-1");
        });
        test("overflow is an error", () -> {
            fails("9223372036854775807+1");
            fails("3037000500*3037000500");
            fails("2^63");
            fails("9223372036854775808");
            fails("-9223372036854775807-2");
            fails("(0-9223372036854775807-1)/-1");
        });
        test("values right at the limit are fine", () -> {
            eq("2^62", 4611686018427387904L);
            eq("9223372036854775807", Long.MAX_VALUE);
            eq("-9223372036854775807-1", Long.MIN_VALUE);
            eq("3037000499*3037000499", 9223372030926249001L);
        });
        test("malformed expressions are errors", () -> {
            fails("");
            fails("   ");
            fails("()");
            fails("(1");
            fails("1)");
            fails("1+");
            fails("*2");
            fails("2**3");
            fails("1 2");
            fails("a+1");
            fails("1.5+1");
            fails("(1+2)(3)");
            fails("2 3 +");
        });
        test("null is an error", () -> fails(null));
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
