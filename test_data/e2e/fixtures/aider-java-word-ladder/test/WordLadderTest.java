import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class WordLadderTest {
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

    static void ladders(String start, String end, List<String> dictionary, List<List<String>> expected) {
        test("ladders " + start + " -> " + end, () -> eq(expected, WordLadder.shortestLadders(start, end, dictionary)));
    }

    static List<String> allWords(String alphabet, int length) {
        List<String> words = new ArrayList<>();
        words.add("");
        for (int i = 0; i < length; i++) {
            List<String> next = new ArrayList<>();
            for (String w : words) {
                for (char c : alphabet.toCharArray()) next.add(w + c);
            }
            words = next;
        }
        return words;
    }

    static void checkLadders(List<List<String>> actual, int count, int words, List<String> first, List<String> last) {
        eq(count, actual.size());
        eq(first, actual.get(0));
        eq(last, actual.get(actual.size() - 1));
        for (int i = 0; i < actual.size(); i++) {
            List<String> ladder = actual.get(i);
            eq(words, ladder.size());
            for (int k = 1; k < ladder.size(); k++) {
                int diff = 0;
                for (int p = 0; p < ladder.get(k).length(); p++) {
                    if (ladder.get(k).charAt(p) != ladder.get(k - 1).charAt(p)) diff++;
                }
                eq(1, diff);
            }
            if (i > 0) {
                check(compare(actual.get(i - 1), ladder) < 0, "ladders not strictly sorted at " + i);
            }
        }
    }

    static int compare(List<String> a, List<String> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    }

    public static void main(String[] args) {
        ladders("cold", "warm", List.of("cord", "card", "ward", "warm", "corm", "worm", "word", "wold", "cold", "core", "wore", "worn", "mold", "mild"), List.of(List.of("cold", "cord", "card", "ward", "warm"), List.of("cold", "cord", "corm", "worm", "warm"), List.of("cold", "cord", "word", "ward", "warm"), List.of("cold", "cord", "word", "worm", "warm"), List.of("cold", "wold", "word", "ward", "warm"), List.of("cold", "wold", "word", "worm", "warm")));
        ladders("tape", "tone", List.of("tone", "tome", "tale", "tole", "time", "tine", "tame", "bone", "hone", "tonk", "tile", "tole", "tome"), List.of(List.of("tape", "tale", "tole", "tone"), List.of("tape", "tame", "tome", "tone")));
        ladders("abc", "acd", List.of("abc", "abd", "acd", "xyz", "xyy", "xzz", "abcd", "ab"), List.of(List.of("abc", "abd", "acd")));
        ladders("abc", "xyz", List.of("abc", "abd", "acd", "xyz", "xyy", "xzz", "abcd", "ab"), List.of());
        ladders("dog", "cog", List.of("dog", "dot", "cot", "cog", "log", "lot", "dig", "dug"), List.of(List.of("dog", "cog")));
        ladders("dig", "cot", List.of("dog", "dot", "cot", "cog", "log", "lot", "dig", "dug"), List.of(List.of("dig", "dog", "cog", "cot"), List.of("dig", "dog", "dot", "cot")));
        ladders("dog", "dog", List.of("dog", "dot", "cot", "cog", "log", "lot", "dig", "dug"), List.of(List.of("dog")));
        ladders("hat", "dog", List.of("dog", "dot", "cot", "cog", "log", "lot", "dig", "dug"), List.of());
        ladders("dog", "bat", List.of("dog", "dot", "cot", "cog", "log", "lot", "dig", "dug"), List.of());

        test("four positions over a small alphabet: 24 orders of the changes", () -> {
            List<List<String>> actual = WordLadder.shortestLadders("aaaa", "cccc", allWords("abc", 4));
            checkLadders(actual, 24, 5, List.of("aaaa", "aaac", "aacc", "accc", "cccc"), List.of("aaaa", "caaa", "ccaa", "ccca", "cccc"));
        });

        test("binary words: 120 shortest ladders", () -> {
            List<List<String>> actual = WordLadder.shortestLadders("aaaaa", "bbbbb", allWords("ab", 5));
            checkLadders(actual, 120, 6, List.of("aaaaa", "aaaab", "aaabb", "aabbb", "abbbb", "bbbbb"), List.of("aaaaa", "baaaa", "bbaaa", "bbbaa", "bbbba", "bbbbb"));
        });

        test("a dictionary of 7776 words is handled quickly", () -> {
            long started = System.nanoTime();
            List<List<String>> actual = WordLadder.shortestLadders("aaaaa", "fffff", allWords("abcdef", 5));
            long millis = (System.nanoTime() - started) / 1_000_000;
            eq(120, actual.size());
            eq(6, actual.get(0).size());
            check(millis < 8000, "too slow: " + millis + " ms");
        });

        test("start may be in the dictionary and is never revisited", () -> {
            List<String> dictionary = Arrays.asList("hit", "hot", "dot", "dog", "cog", "lot", "log");
            List<List<String>> actual = WordLadder.shortestLadders("hit", "cog", dictionary);
            eq(List.of(List.of("hit", "hot", "dot", "dog", "cog"), List.of("hit", "hot", "lot", "log", "cog")), actual);
        });

        test("the dictionary collection is not modified", () -> {
            List<String> dictionary = new ArrayList<>(List.of("dot", "dog", "cog", "dog"));
            List<String> copy = new ArrayList<>(dictionary);
            WordLadder.shortestLadders("dig", "cog", dictionary);
            eq(copy, dictionary);
        });

        test("case matters", () -> eq(List.of(), WordLadder.shortestLadders("dog", "COG", List.of("cog", "COG"))));

        test("any collection type works", () -> {
            Collection<String> set = new java.util.TreeSet<>(List.of("dot", "dog", "cog"));
            eq(List.of(List.of("dit", "dot", "dog", "cog")), WordLadder.shortestLadders("dit", "cog", set));
        });

        System.out.println(run + " tests, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
