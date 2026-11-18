import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ShortDeckPoker {
    private static final String RANKS = "6789TJQKA";
    private static final String SUITS = "CDHS";

    private static final int HIGH_CARD = 0;
    private static final int PAIR = 1;
    private static final int TWO_PAIR = 2;
    private static final int THREE = 3;
    private static final int STRAIGHT = 4;
    private static final int FULL_HOUSE = 5;
    private static final int FLUSH = 6;
    private static final int FOUR = 7;
    private static final int STRAIGHT_FLUSH = 8;

    public static List<String> bestHands(List<String> hands) {
        List<String> winners = new ArrayList<>();
        if (hands.isEmpty()) return winners;
        List<int[]> scores = new ArrayList<>();
        for (String hand : hands) {
            scores.add(score(hand));
        }
        int[] best = scores.get(0);
        for (int[] s : scores) {
            if (compare(s, best) > 0) best = s;
        }
        for (int i = 0; i < hands.size(); i++) {
            if (compare(scores.get(i), best) == 0) winners.add(hands.get(i));
        }
        return winners;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return Integer.compare(a.length, b.length);
    }

    private static int[] score(String hand) {
        Set<String> seen = new HashSet<>();
        String[] cards = hand.trim().split("\\s+");
        if (cards.length != 5) throw new IllegalArgumentException("a hand needs five cards: " + hand);
        int[] ranks = new int[5];
        char suit = 0;
        boolean sameSuit = true;
        for (int i = 0; i < 5; i++) {
            String card = cards[i];
            if (card.length() != 2 || RANKS.indexOf(card.charAt(0)) < 0 || SUITS.indexOf(card.charAt(1)) < 0) {
                throw new IllegalArgumentException("not a short-deck card: " + card);
            }
            if (!seen.add(card)) throw new IllegalArgumentException("card dealt twice: " + card);
            ranks[i] = RANKS.indexOf(card.charAt(0)) + 6;
            if (i == 0) suit = card.charAt(1);
            else if (card.charAt(1) != suit) sameSuit = false;
        }

        int[] sorted = ranks.clone();
        java.util.Arrays.sort(sorted);
        int[] desc = new int[5];
        for (int i = 0; i < 5; i++) desc[i] = sorted[4 - i];

        int straightHigh = straightHigh(desc);

        // groups ordered by size and then rank, both descending
        int[] counts = new int[15];
        for (int r : ranks) counts[r]++;
        List<int[]> groups = new ArrayList<>();
        for (int r = 14; r >= 6; r--) {
            if (counts[r] > 0) groups.add(new int[] {counts[r], r});
        }
        Collections.sort(groups, (x, y) -> x[0] != y[0] ? Integer.compare(y[0], x[0]) : Integer.compare(y[1], x[1]));

        int category;
        int first = groups.get(0)[0];
        int second = groups.size() > 1 ? groups.get(1)[0] : 0;
        if (straightHigh > 0 && sameSuit) category = STRAIGHT_FLUSH;
        else if (first == 4) category = FOUR;
        else if (sameSuit) category = FLUSH;
        else if (first == 3 && second == 2) category = FULL_HOUSE;
        else if (straightHigh > 0) category = STRAIGHT;
        else if (first == 3) category = THREE;
        else if (first == 2 && second == 2) category = TWO_PAIR;
        else if (first == 2) category = PAIR;
        else category = HIGH_CARD;

        if (straightHigh > 0) return new int[] {category, straightHigh};
        int[] score = new int[1 + groups.size()];
        score[0] = category;
        for (int i = 0; i < groups.size(); i++) score[i + 1] = groups.get(i)[1];
        return score;
    }

    /** Returns the high card of a straight, or 0 when the five descending ranks are not one. */
    private static int straightHigh(int[] desc) {
        boolean run = true;
        for (int i = 1; i < 5; i++) {
            if (desc[i - 1] - desc[i] != 1) run = false;
        }
        if (run) return desc[0];
        return 0;
    }
}
