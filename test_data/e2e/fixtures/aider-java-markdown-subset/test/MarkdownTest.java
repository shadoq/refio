public class MarkdownTest {
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

    static void md(String input, String expected) {
        test("convert " + input.replace("\n", "\\n").replace("\r", "\\r"), () -> eq(expected, Markdown.toHtml(input)));
    }

    public static void main(String[] args) {
        md("# Title",
           "<h1>Title</h1>");
        md("###### Deep",
           "<h6>Deep</h6>");
        md("####### Seven",
           "<p>####### Seven</p>");
        md("#NoSpace",
           "<p>#NoSpace</p>");
        md("#",
           "<p>#</p>");
        md("#   Spaced   ",
           "<h1>Spaced</h1>");
        md("# A\n## B",
           "<h1>A</h1><h2>B</h2>");
        md("## A __b__",
           "<h2>A <strong>b</strong></h2>");
        md("# _a_",
           "<h1><em>a</em></h1>");
        md(" # indented",
           "<p># indented</p>");
        md("* a\n* b",
           "<ul><li>a</li><li>b</li></ul>");
        md("* a\n\n* b",
           "<ul><li>a</li></ul><ul><li>b</li></ul>");
        md("* one _two_",
           "<ul><li>one <em>two</em></li></ul>");
        md("*not a list",
           "<p>*not a list</p>");
        md("* ",
           "<p>*</p>");
        md("* a\n# H\n* b",
           "<ul><li>a</li></ul><h1>H</h1><ul><li>b</li></ul>");
        md("* a\ntext",
           "<ul><li>a</li></ul><p>text</p>");
        md("text\n* a",
           "<p>text</p><ul><li>a</li></ul>");
        md("* _a_\n* __b__",
           "<ul><li><em>a</em></li><li><strong>b</strong></li></ul>");
        md("* _a\n* b_",
           "<ul><li>_a</li><li>b_</li></ul>");
        md("a\nb",
           "<p>a b</p>");
        md("a\n\nb",
           "<p>a</p><p>b</p>");
        md("\n\n a  \n\n\n b\n\n",
           "<p>a</p><p>b</p>");
        md("",
           "");
        md("   \n  ",
           "");
        md("a\r\nb\r\n\r\nc",
           "<p>a b</p><p>c</p>");
        md("text\n# H",
           "<p>text</p><h1>H</h1>");
        md("# H\ntext",
           "<h1>H</h1><p>text</p>");
        md("__bold__ and _italic_",
           "<p><strong>bold</strong> and <em>italic</em></p>");
        md("__bold _both_ text__",
           "<p><strong>bold <em>both</em> text</strong></p>");
        md("_it __both__ it_",
           "<p><em>it <strong>both</strong> it</em></p>");
        md("snake_case_name",
           "<p>snake_case_name</p>");
        md("__init__ and snake_case",
           "<p><strong>init</strong> and snake_case</p>");
        md("_a_b_",
           "<p><em>a_b</em></p>");
        md("_unclosed",
           "<p>_unclosed</p>");
        md("closed_",
           "<p>closed_</p>");
        md("__a_",
           "<p>__a_</p>");
        md("_a__",
           "<p>_a__</p>");
        md("___x___",
           "<p>___x___</p>");
        md("a _ b _ c",
           "<p>a _ b _ c</p>");
        md("__a__b",
           "<p>__a__b</p>");
        md("__ bold__",
           "<p>__ bold__</p>");
        md("_a_ _b_",
           "<p><em>a</em> <em>b</em></p>");
        md("_x_.",
           "<p><em>x</em>.</p>");
        md("(_x_)",
           "<p>(<em>x</em>)</p>");
        md("_(x)_",
           "<p><em>(x)</em></p>");
        md("_a\nb_",
           "<p><em>a b</em></p>");
        md("__a _b__ c_",
           "<p><strong>a _b</strong> c_</p>");
        md("a < b & c > d",
           "<p>a &lt; b &amp; c &gt; d</p>");
        md("<_x_>",
           "<p>&lt;<em>x</em>&gt;</p>");
        md("# <b> & co",
           "<h1>&lt;b&gt; &amp; co</h1>");
        md("* 1 < 2",
           "<ul><li>1 &lt; 2</li></ul>");
        md("# Doc\n\nIntro __bold__ & _it_.\n\n* one\n* two\n\n####### not header\n",
           "<h1>Doc</h1><p>Intro <strong>bold</strong> &amp; <em>it</em>.</p><ul><li>one</li><li>two</li></ul><p>####### not header</p>");

        System.out.println(run + " tests, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
