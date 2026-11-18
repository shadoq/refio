import java.util.ArrayList;
import java.util.List;

public class ChangeMaker {
    public static List<Integer> change(int amount, List<Integer> denominations) {
        if (amount < 0) throw new IllegalArgumentException("Negative totals are not allowed.");
        for (int c : denominations) if (c <= 0) throw new IllegalArgumentException("Denominations must be positive.");
        List<Integer> coins = new ArrayList<>(new java.util.TreeSet<>(denominations));
        int[] dp = new int[amount + 1];
        for (int a = 1; a <= amount; a++) {
            dp[a] = Integer.MAX_VALUE / 2;
            for (int c : coins) if (c <= a && dp[a - c] + 1 < dp[a]) dp[a] = dp[a - c] + 1;
        }
        if (dp[amount] >= Integer.MAX_VALUE / 2) throw new IllegalArgumentException("The total " + amount + " cannot be represented in the given currency.");
        List<Integer> result = new ArrayList<>();
        int left = amount;
        while (left > 0) {
            for (int i = coins.size() - 1; i >= 0; i--) {
                int c = coins.get(i);
                if (c <= left && dp[left - c] + 1 == dp[left]) {
                    result.add(c);
                    left -= c;
                    break;
                }
            }
        }
        result.sort(null);
        return result;
    }
}
