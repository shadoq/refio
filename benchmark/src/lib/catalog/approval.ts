// The approval gate a headless Refio run needs. In headless there is no human to
// approve anything, so every ASK-level command tool waits out its timeout and is
// recorded as "User rejected" - which ends the turn FAILED even when the edit already
// landed. Without this the benchmark measured a Refio that could not run a single
// build or test: shell calls zero on every task, self-verification zero on every task,
// and the numbers read as behaviour rather than as a blocked tool.
//
// The same expression drives tools/e2e/e2e-run.sh; a test asserts the two stay
// identical, because a comparison whose two harnesses approve different commands is
// not a comparison. Write and edit tools are not ASK in AGENT mode, so the gate only
// covers command tools; the project is a throwaway temp dir, and Refio's own denylist
// still guards destructive forms after approval. Deletion is deliberately absent, and
// curl is allowed only against loopback.
export const HEADLESS_AUTO_APPROVE =
  "(\\bcurl\\b(?!.*://(?!(?:127\\.0\\.0\\.1|localhost|\\[::1\\])(?::\\d+)?(?=[/?\\s]|$)))(?=.*(?:127\\.0\\.0\\.1|localhost|\\[::1\\])(?::\\d+)?(?=[/?\\s]|$))|\\b(kotlinc?|gradlew|gradle|javac|java|python3?|pip3?|node|npm|npx|pnpm|yarn|pytest|mvn|cargo|go|make|cmake|ls|cat|pwd|echo|head|tail|sed|awk|grep|rg|find|wc|diff|test|true|cd|sh|bash|env|export|mkdir|touch|mv|cp|tr|sort|uniq|cut|sleep|which|lsof)\\b)";
