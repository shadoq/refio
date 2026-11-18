import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WordLadder {
    public static List<List<String>> shortestLadders(String start, String end, Collection<String> dictionary) {
        List<List<String>> result = new ArrayList<>();
        if (start.length() != end.length()) return result;
        if (start.equals(end)) {
            result.add(new ArrayList<>(List.of(start)));
            return result;
        }
        Set<String> words = new HashSet<>();
        for (String w : dictionary) {
            if (w.length() == start.length()) words.add(w);
        }
        if (!words.contains(end)) return result;
        words.add(start);

        Map<String, List<String>> buckets = new HashMap<>();
        for (String w : words) {
            for (int i = 0; i < w.length(); i++) {
                buckets.computeIfAbsent(w.substring(0, i) + '*' + w.substring(i + 1), k -> new ArrayList<>()).add(w);
            }
        }

        Map<String, Integer> distance = new HashMap<>();
        Map<String, List<String>> parents = new HashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        distance.put(start, 0);
        queue.add(start);
        while (!queue.isEmpty()) {
            String word = queue.poll();
            int next = distance.get(word) + 1;
            if (distance.containsKey(end) && next > distance.get(end)) break;
            for (int i = 0; i < word.length(); i++) {
                for (String neighbour : buckets.get(word.substring(0, i) + '*' + word.substring(i + 1))) {
                    if (neighbour.equals(word)) continue;
                    Integer known = distance.get(neighbour);
                    if (known == null) {
                        distance.put(neighbour, next);
                        parents.computeIfAbsent(neighbour, k -> new ArrayList<>()).add(word);
                        queue.add(neighbour);
                    } else if (known == next) {
                        parents.get(neighbour).add(word);
                    }
                }
            }
        }
        if (!distance.containsKey(end)) return result;

        collect(end, start, parents, new LinkedList<>(), result);
        result.sort(WordLadder::compare);
        return result;
    }

    private static void collect(String word, String start, Map<String, List<String>> parents, LinkedList<String> path, List<List<String>> out) {
        path.addFirst(word);
        if (word.equals(start)) {
            out.add(new ArrayList<>(path));
        } else {
            for (String parent : parents.get(word)) collect(parent, start, parents, path, out);
        }
        path.removeFirst();
    }

    private static int compare(List<String> a, List<String> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    }
}
