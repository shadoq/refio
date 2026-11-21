import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ShortDeckPokerTest {
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

    static List<String> best(String... hands) {
        return ShortDeckPoker.bestHands(Arrays.asList(hands));
    }

    static void winner(String expected, String... hands) {
        eq(List.of(expected), best(hands));
    }

    public static void main(String[] args) {
        test("a single hand wins", () -> winner("6H 7D 9C JS KH", "6H 7D 9C JS KH"));

        test("empty input gives empty output", () -> eq(List.of(), ShortDeckPoker.bestHands(new ArrayList<>())));

        test("every category beats the one below it, in either input order", () -> {
            String[] ladder = {
            "6H 8D TC QS AH",
            "6H 6D 9C JS KH",
            "6H 6D 9C 9S KH",
            "7H 7D 7C JS KH",
            "6H 7D 8C 9S TH",
            "9H 9D 9C KS KH",
            "6H 8H TH QH AH",
            "9H 9D 9C 9S KH",
            "6D 7D 8D 9D TD"
            };
            for (int i = 1; i < ladder.length; i++) {
                eq(List.of(ladder[i]), best(ladder[i - 1], ladder[i]));
                eq(List.of(ladder[i]), best(ladder[i], ladder[i - 1]));
            }
        });

        test("a flush beats a full house", () -> winner("6H 8H TH QH AH", "KH KD KC QS QH", "6H 8H TH QH AH"));

        test("the best flush beats a lower flush, the best full house a lower one", () -> {
            winner("AD KD 9D 7D 8D", "AH KH 9H 7H 6H", "AD KD 9D 7D 8D");
            winner("AH AD AC 6S 6H", "KH KD KC QS QH", "AH AD AC 6S 6H");
        });

        test("A 6 7 8 9 is a straight", () -> winner("AH 6D 7C 8S 9H", "AH 6D 7C 8S 9H", "KH QD JC TS 6H"));

        test("the ace-low straight is the lowest straight", () -> winner("6H 7D 8C 9S TH", "AH 6D 7C 8S 9H", "6H 7D 8C 9S TH"));

        test("the ace-low straight still beats three of a kind", () -> winner("AH 6D 7C 8S 9H", "AH AD AC 7S 6H", "AH 6D 7C 8S 9H"));

        test("two ace-low straights tie whatever the suits", () ->
            eq(List.of("AH 6D 7C 8S 9H", "AC 6S 7H 8D 9C"), best("AH 6D 7C 8S 9H", "AC 6S 7H 8D 9C", "6C 6D 7S 9H KD")));

        test("the ace-low straight flush is below the six-high straight flush", () ->
            winner("6D 7D 8D 9D TD", "AH 6H 7H 8H 9H", "6D 7D 8D 9D TD"));

        test("the ace-low straight flush beats four of a kind", () ->
            winner("AH 6H 7H 8H 9H", "AH AD AC AS 6H", "AH 6H 7H 8H 9H"));

        test("the ace-high straight is the highest straight", () ->
            winner("TH JD QC KS AH", "9H TD JC QS KH", "TH JD QC KS AH"));

        test("there is no other wrap-around: Q K A 6 7 is only ace-high", () ->
            winner("6C 6D 8S TH QD", "QH KD AC 6S 7H", "6C 6D 8S TH QD"));

        test("K A 6 7 8 is not a straight either", () -> winner("6C 6D 8S TH QD", "KH AD 6C 7S 8H", "6C 6D 8S TH QD"));

        test("pairs compare the pair first and then the kickers from high to low", () -> {
            winner("9H 9D 6C 7S AH", "9C 9S 6D 7H KH", "9H 9D 6C 7S AH");
            winner("9H 9D AC 7S 6H", "9C 9S KD QH JS", "9H 9D AC 7S 6H");
            winner("TH TD 6C 7S 8H", "9C 9S AD KH QS", "TH TD 6C 7S 8H");
        });

        test("two pair compares the higher pair, then the lower pair, then the kicker", () -> {
            winner("KH KD 6C 6S AH", "QH QD JC JS AD", "KH KD 6C 6S AH");
            winner("QH QD 9C 9S 6H", "QC QS 8D 8H AH", "QH QD 9C 9S 6H");
            winner("QH QD 9C 9S AH", "QC QS 9D 9H KH", "QH QD 9C 9S AH");
        });

        test("full house compares the triple before the pair", () ->
            winner("7H 7D 7C AS AH", "6H 6D 6C 8S 8H", "7H 7D 7C AS AH"));

        test("three of a kind compares the triple and then the kickers", () -> {
            winner("8H 8D 8C 6S 7H", "7C 7S 7D AH KH", "8H 8D 8C 6S 7H");
            winner("8H 8D 8C AS 7H", "8S 8C 8D KH QH", "8H 8D 8C AS 7H");
        });

        test("four of a kind compares the quad and then the kicker", () -> {
            winner("9H 9D 9C 9S 6H", "8H 8D 8C 8S AH", "9H 9D 9C 9S 6H");
            winner("9H 9D 9C 9S KH", "9H 9D 9C 9S TH", "9H 9D 9C 9S KH");
        });

        test("high card compares all five ranks from the top", () -> {
            winner("AH KD 9C 8S 7H", "AC KS 9D 8H 6C", "AH KD 9C 8S 7H");
            winner("AH KD TC 8S 7H", "AC KS 9D 8H 7C", "AH KD TC 8S 7H");
        });

        test("ties return every winner in input order", () -> {
            eq(List.of("9H 9D AC 7S 6H", "9C 9S AD 7H 6D"),
                best("9H 9D AC 7S 6H", "KH QD JC TS 8H", "9C 9S AD 7H 6D"));
            eq(List.of("6H 8D TC QS AH", "6C 8S TD QH AC"), best("6H 8D TC QS AH", "6C 8S TD QH AC"));
        });

        test("the best hand among many is found", () ->
            winner("6D 6C 6S 6H AD", "AH KD JC TS 8H", "9H 9D AC 7S 6H", "7H 7D 7C JS KH", "6D 6C 6S 6H AD", "9H 9D 9S AC AD"));

        test("the winners are returned as written, without trimming", () -> {
            eq(List.of("  6H 7D   8C 9S TH "), best("  6H 7D   8C 9S TH ", "6C 6D 8S TH QD"));
        });

        test("the input list is not modified", () -> {
            List<String> hands = new ArrayList<>(List.of("6H 7D 9C JS KH", "9H 9D 6C 7S AH"));
            ShortDeckPoker.bestHands(hands);
            eq(List.of("6H 7D 9C JS KH", "9H 9D 6C 7S AH"), hands);
        });

        test("wrong number of cards is rejected", () -> {
            throwsA(IllegalArgumentException.class, () -> best("6H 7D 8C 9S"));
            throwsA(IllegalArgumentException.class, () -> best("6H 7D 8C 9S TH JH"));
            throwsA(IllegalArgumentException.class, () -> best(""));
            throwsA(IllegalArgumentException.class, () -> best("6H 7D 8C 9S TH", "6C 7S"));
        });

        test("cards outside the 36-card deck are rejected", () -> {
            throwsA(IllegalArgumentException.class, () -> best("5H 7D 8C 9S TH"));
            throwsA(IllegalArgumentException.class, () -> best("2H 7D 8C 9S TH"));
            throwsA(IllegalArgumentException.class, () -> best("10H 7D 8C 9S JH"));
            throwsA(IllegalArgumentException.class, () -> best("6X 7D 8C 9S TH"));
            throwsA(IllegalArgumentException.class, () -> best("6h 7D 8C 9S TH"));
            throwsA(IllegalArgumentException.class, () -> best("6H 7D 8C 9S Th"));
            throwsA(IllegalArgumentException.class, () -> best("6H7D 8C 9S TH JH"));
        });

        test("a repeated card inside one hand is rejected", () -> {
            throwsA(IllegalArgumentException.class, () -> best("6H 6H 8C 9S TH"));
            throwsA(IllegalArgumentException.class, () -> best("6H 7D 8C 9S TH", "AC AD AH AS AS"));
        });

        System.out.println(run + " tests, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
