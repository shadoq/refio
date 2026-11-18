import java.util.ArrayList;
import java.util.List;

public class ChangeMaker {
    public static List<Integer> change(int amount, List<Integer> denominations) {
        if (amount < 0) throw new IllegalArgumentException("Negative totals are not allowed.");
        List<Integer> sorted = new ArrayList<>(denominations);
        for (int c : sorted) if (c <= 0) throw new IllegalArgumentException("Denominations must be positive.");
        sorted.sort((a, b) -> b - a);
        List<Integer> result = new ArrayList<>();
        int left = amount;
        for (int coin : sorted) {
            while (left >= coin) {
                result.add(coin);
                left -= coin;
            }
        }
        if (left != 0) throw new IllegalArgumentException("The total " + amount + " cannot be represented in the given currency.");
        result.sort(null);
        return result;
    }
}
