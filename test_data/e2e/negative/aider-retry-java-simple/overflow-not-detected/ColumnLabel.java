public final class ColumnLabel {
    private ColumnLabel() {
    }

    public static String toLabel(int column) {
        if (column < 1) {
            throw new IllegalArgumentException("column must be at least 1: " + column);
        }
        StringBuilder sb = new StringBuilder();
        int n = column;
        while (n > 0) {
            n--;
            sb.append((char) ('A' + n % 26));
            n /= 26;
        }
        return sb.reverse().toString();
    }

    public static int fromLabel(String label) {
        if (label == null || label.isEmpty()) {
            throw new IllegalArgumentException("label must not be empty");
        }
        int result = 0;
        for (int i = 0; i < label.length(); i++) {
            char c = Character.toUpperCase(label.charAt(i));
            if (c < 'A' || c > 'Z' || label.charAt(i) > 'z') {
                throw new IllegalArgumentException("invalid label: " + label);
            }
            result = result * 26 + (c - 'A' + 1);
        }
        return result;
    }
}
