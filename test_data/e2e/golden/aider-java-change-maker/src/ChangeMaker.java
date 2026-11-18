import java.util.ArrayList;
import java.util.List;

public class ChangeMaker {
    private static final int UNREACHABLE = Integer.MAX_VALUE / 2;

    public static List<Integer> change(int amount, List<Integer> denominations) {
        if (amount < 0) throw new IllegalArgumentException("Negative totals are not allowed.");
        int[] coins = denominations.stream().mapToInt(Integer::intValue).distinct().sorted().toArray();
        for (int coin : coins) {
            if (coin <= 0) throw new IllegalArgumentException("Denominations must be positive.");
        }
        List<Integer> result = new ArrayList<>();
        if (amount == 0) return result;

        int n = coins.length;
        // fewest[i][a]: fewest coins for a using only coins[i..n-1]
        int[][] fewest = new int[n + 1][amount + 1];
        for (int i = 0; i <= n; i++) {
            for (int a = 1; a <= amount; a++) fewest[i][a] = UNREACHABLE;
        }
        for (int i = n - 1; i >= 0; i--) {
            for (int a = 1; a <= amount; a++) {
                int best = fewest[i + 1][a];
                if (a >= coins[i] && fewest[i][a - coins[i]] + 1 < best) best = fewest[i][a - coins[i]] + 1;
                fewest[i][a] = best;
            }
        }
        if (fewest[0][amount] >= UNREACHABLE) {
            throw new IllegalArgumentException("The total " + amount + " cannot be represented in the given currency.");
        }

        int from = 0;
        int left = amount;
        while (left > 0) {
            for (int j = from; j < n; j++) {
                if (left >= coins[j] && fewest[j][left - coins[j]] + 1 == fewest[from][left]) {
                    result.add(coins[j]);
                    left -= coins[j];
                    from = j;
                    break;
                }
            }
        }
        return result;
    }
}
