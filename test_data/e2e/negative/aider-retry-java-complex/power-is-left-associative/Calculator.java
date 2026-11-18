public final class Calculator {
    private final String s;
    private int pos;

    private Calculator(String s) {
        this.s = s;
    }

    public static long evaluate(String expression) {
        if (expression == null) {
            throw new CalcException("expression is null");
        }
        Calculator c = new Calculator(expression);
        try {
            long value = c.expr();
            if (c.peek() != -1) {
                throw new CalcException("unexpected '" + (char) c.peek() + "' at " + c.pos);
            }
            return value;
        } catch (ArithmeticException e) {
            throw new CalcException("arithmetic error: " + e.getMessage());
        }
    }

    private int peek() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
        return pos < s.length() ? s.charAt(pos) : -1;
    }

    private long expr() {
        long value = term();
        while (true) {
            int c = peek();
            if (c == '+') {
                pos++;
                value = Math.addExact(value, term());
            } else if (c == '-') {
                pos++;
                value = Math.subtractExact(value, term());
            } else {
                return value;
            }
        }
    }

    private long term() {
        long value = unary();
        while (true) {
            int c = peek();
            if (c == '*') {
                pos++;
                value = Math.multiplyExact(value, unary());
            } else if (c == '/' || c == '%') {
                pos++;
                long right = unary();
                if (right == 0) {
                    throw new CalcException("division by zero");
                }
                if (c == '/') {
                    if (value == Long.MIN_VALUE && right == -1) {
                        throw new CalcException("overflow");
                    }
                    value = value / right;
                } else {
                    value = value % right;
                }
            } else {
                return value;
            }
        }
    }

    private long unary() {
        int c = peek();
        if (c == '-') {
            pos++;
            return Math.negateExact(unary());
        }
        if (c == '+') {
            pos++;
            return unary();
        }
        return power();
    }

    private long power() {
        long base = atom();
        while (peek() == '^') {
            pos++;
            base = pow(base, atom());
        }
        return base;
    }

    private static long pow(long base, long exponent) {
        if (exponent < 0) {
            throw new CalcException("negative exponent");
        }
        if (exponent == 0) {
            return 1;
        }
        if (base == 0 || base == 1) {
            return base;
        }
        if (base == -1) {
            return exponent % 2 == 0 ? 1 : -1;
        }
        if (exponent > 63) {
            throw new CalcException("overflow");
        }
        long result = 1;
        for (long i = 0; i < exponent; i++) {
            result = Math.multiplyExact(result, base);
        }
        return result;
    }

    private long atom() {
        int c = peek();
        if (c == '(') {
            pos++;
            long value = expr();
            if (peek() != ')') {
                throw new CalcException("expected ')' at " + pos);
            }
            pos++;
            return value;
        }
        if (c >= '0' && c <= '9') {
            int start = pos;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
            }
            try {
                return Long.parseLong(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new CalcException("number out of range: " + s.substring(start, pos));
            }
        }
        throw new CalcException(c == -1 ? "unexpected end of expression" : "unexpected '" + (char) c + "' at " + pos);
    }
}
