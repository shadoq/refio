import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Markdown {
    private static final Pattern HEADER = Pattern.compile("^(#{1,6}) +(\\S.*?)\\s*$");

    public static String toHtml(String markdown) {
        StringBuilder out = new StringBuilder();
        List<String> paragraph = new ArrayList<>();
        boolean inList = false;

        for (String line : markdown.split("\r?\n", -1)) {
            if (line.trim().isEmpty()) {
                flush(out, paragraph);
                inList = closeList(out, inList);
                continue;
            }
            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                flush(out, paragraph);
                inList = closeList(out, inList);
                int level = header.group(1).length();
                out.append("<h").append(level).append('>').append(inline(header.group(2))).append("</h").append(level).append('>');
            } else if (line.startsWith("* ") && !line.substring(2).trim().isEmpty()) {
                flush(out, paragraph);
                if (!inList) {
                    out.append("<ul>");
                    inList = true;
                }
                out.append("<li>").append(inline(line.substring(2).trim())).append("</li>");
            } else {
                inList = closeList(out, inList);
                paragraph.add(line.trim());
            }
        }
        flush(out, paragraph);
        closeList(out, inList);
        return out.toString();
    }

    private static boolean closeList(StringBuilder out, boolean inList) {
        if (inList) out.append("</ul>");
        return false;
    }

    private static void flush(StringBuilder out, List<String> paragraph) {
        if (paragraph.isEmpty()) return;
        out.append("<p>").append(inline(String.join(" ", paragraph))).append("</p>");
        paragraph.clear();
    }

    private static final class Frame {
        final int length;
        final StringBuilder content = new StringBuilder();

        Frame(int length) {
            this.length = length;
        }
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c);
    }

    private static String inline(String s) {
        List<Frame> stack = new ArrayList<>();
        stack.add(new Frame(0));
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '_') {
                escapeInto(top(stack).content, c);
                i++;
                continue;
            }
            int j = i;
            while (j < n && s.charAt(j) == '_') j++;
            int length = j - i;
            if (length >= 3) {
                top(stack).content.append(s, i, j);
                i = j;
                continue;
            }
            boolean hasPrev = i > 0;
            boolean hasNext = j < n;
            char prev = hasPrev ? s.charAt(i - 1) : 0;
            char next = hasNext ? s.charAt(j) : 0;
            boolean canOpen = (!hasPrev || !isWord(prev)) && hasNext && !Character.isWhitespace(next);
            boolean canClose = hasPrev && !Character.isWhitespace(prev) && (!hasNext || !isWord(next));

            int match = -1;
            if (canClose) {
                for (int k = stack.size() - 1; k >= 1; k--) {
                    if (stack.get(k).length == length) {
                        match = k;
                        break;
                    }
                }
            }
            if (match >= 0) {
                while (stack.size() - 1 > match) {
                    Frame dropped = stack.remove(stack.size() - 1);
                    top(stack).content.append("_".repeat(dropped.length)).append(dropped.content);
                }
                Frame done = stack.remove(stack.size() - 1);
                String tag = length == 2 ? "strong" : "em";
                top(stack).content.append('<').append(tag).append('>').append(done.content).append("</").append(tag).append('>');
            } else if (canOpen) {
                stack.add(new Frame(length));
            } else {
                top(stack).content.append(s, i, j);
            }
            i = j;
        }
        while (stack.size() > 1) {
            Frame dropped = stack.remove(stack.size() - 1);
            top(stack).content.append("_".repeat(dropped.length)).append(dropped.content);
        }
        return stack.get(0).content.toString();
    }

    private static Frame top(List<Frame> stack) {
        return stack.get(stack.size() - 1);
    }

    private static void escapeInto(StringBuilder out, char c) {
        switch (c) {
            default: out.append(c);
        }
    }
}
