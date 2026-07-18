package pl.jclab.refio.core.tools.security

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class CommandRuleMatcherTest {

    @Nested
    inner class PriorityTests {
        @Test
        fun `BLOCK takes priority over ALLOW`() {
            val matcher = CommandRuleMatcher(listOf(
                CommandRule("^rm\\s+-rf", RuleAction.BLOCK, "Block rm -rf"),
                CommandRule("^rm(\\s+.*)?$", RuleAction.ALLOW, "Allow rm")
            ))

            val result = matcher.match("rm -rf /tmp")
            assertEquals(RuleAction.BLOCK, result.action)
        }

        @Test
        fun `ALLOW takes priority over ASK`() {
            val matcher = CommandRuleMatcher(listOf(
                CommandRule("^git(\\s+.*)?$", RuleAction.ALLOW, "Allow git"),
                CommandRule(".*", RuleAction.ASK, "Ask everything")
            ))

            val result = matcher.match("git status")
            assertEquals(RuleAction.ALLOW, result.action)
        }

        @Test
        fun `default is ASK when no rule matches`() {
            val matcher = CommandRuleMatcher(emptyList())

            val result = matcher.match("unknown-program arg1 arg2")
            assertEquals(RuleAction.ASK, result.action)
            assertEquals(null, result.matchedRule)
        }
    }

    @Nested
    inner class BlockTests {
        @Test
        fun `should block git reset --hard`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.BLOCK, matcher.match("git reset --hard HEAD~1").action)
        }

        @Test
        fun `should block git push --force`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.BLOCK, matcher.match("git push --force origin main").action)
        }

        // git clean -f deletes untracked files. The force flag must be caught in any
        // order/grouping, not only `-f` immediately after `clean` (a reordered or long
        // `--force` form previously fell through to auto-ALLOW and ran with no prompt).
        @Test
        fun `should block git clean -fdx`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.BLOCK, matcher.match("git clean -fdx").action)
        }

        @Test
        fun `should block git clean with grouped force flags -xfd`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.BLOCK, matcher.match("git clean -xfd").action)
        }

        @Test
        fun `should block git clean with separated flags -d -x -f`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.BLOCK, matcher.match("git clean -d -x -f").action)
        }

        @Test
        fun `should block git clean --force long flag`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.BLOCK, matcher.match("git clean --force").action)
        }

        @Test
        fun `should block rm -rf`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.BLOCK, matcher.match("rm -rf /").action)
        }

        @Test
        fun `should block npm publish`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.BLOCK, matcher.match("npm publish").action)
        }

        @Test
        fun `should block mkfs`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.BLOCK, matcher.match("mkfs.ext4 /dev/sda1").action)
        }

        // Windows / PowerShell destructive deletes — the agent runs through
        // powershell.exe, where these verbs alias Remove-Item and escaped the
        // POSIX `rm` rules entirely (a real benchmark FAILED session deleted its
        // own deliverable via `del`). Mirror the recursive/force rm philosophy.
        @Test
        fun `should block del with quiet flag`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.BLOCK, matcher.match("del *.html /q").action)
        }

        @Test
        fun `should block rmdir recursive (Windows)`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.BLOCK, matcher.match("rmdir build /s").action)
        }

        @Test
        fun `should block Remove-Item -Recurse (PowerShell)`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.BLOCK, matcher.match("Remove-Item .\\dist -Recurse").action)
        }

        @Test
        fun `should block del -Force PowerShell alias regardless of case`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.BLOCK, matcher.match("DEL site.html -Force").action)
        }
    }

    @Nested
    inner class AllowTests {
        @Test
        fun `should allow git status`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ALLOW, matcher.match("git status").action)
        }

        // The force-clean block must not catch non-destructive dry runs (no force flag).
        @Test
        fun `should still allow git clean -n dry run`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ALLOW, matcher.match("git clean -n").action)
        }

        @Test
        fun `should still allow git clean --dry-run`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ALLOW, matcher.match("git clean --dry-run").action)
        }

        @Test
        fun `should ask before running npm scripts`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("npm test").action)
        }

        @Test
        fun `should allow ls`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ALLOW, matcher.match("ls -la").action)
        }

        @Test
        fun `should ask before running build tools`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("gradlew build").action)
        }

        @Test
        fun `should allow cat`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ALLOW, matcher.match("cat README.md").action)
        }
    }

    @Nested
    inner class AskTests {
        @Test
        fun `should ask before running interpreter code`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("python script.py").action)
            assertEquals(RuleAction.ASK, matcher.match("node script.js").action)
        }

        @Test
        fun `should ask for docker commands`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("docker run nginx").action)
        }

        @Test
        fun `should ask for ssh`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("ssh user@host").action)
        }

        @Test
        fun `should ask for sudo`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("sudo apt-get install foo").action)
        }

        @Test
        fun `should ask for unknown commands`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("totally-unknown-program --flag").action)
        }

        // Parity with plain `rm <file>` (ASK, not BLOCK): a single-file delete with
        // no recursive/force flag must NOT be hard-blocked — that would break legit
        // cleanup. The deliverable-deletion failure is fenced by the system prompt
        // (don't delete your own just-written file), not by hard-blocking `del`.
        @Test
        fun `should ask for plain single-file del`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("del website_museum.html").action)
        }
    }

    @Nested
    inner class ShellInjectionTests {
        // An ALLOW rule validates only the leading program (`^git(\s+.*)?$`), but the
        // command string is executed whole. So `git status; rm -rf /` matches the git
        // ALLOW rule and would auto-run the appended `rm -rf /` — the leading token is
        // vetted, the chained command is not. A command carrying a shell chaining /
        // substitution operator must never be auto-approved; it falls through to ASK so
        // the user sees and approves the full line.

        @Test
        fun `chained command after allowed program is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("git status; rm -rf /").action)
        }

        @Test
        fun `and-chained command after allowed program is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("cat README.md && rm -rf /").action)
        }

        @Test
        fun `piped command from allowed program is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("git log | sh").action)
        }

        @Test
        fun `command substitution in allowed program is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("echo $(rm -rf /)").action)
        }

        @Test
        fun `backtick substitution in allowed program is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("echo `rm -rf /`").action)
        }

        @Test
        fun `newline-injected second command is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("git status\nrm -rf /").action)
        }

        // A BLOCK rule still wins even when wrapped behind an allowed program — the BLOCK
        // phase runs first and uses containsMatchIn, so the destructive tail is caught.
        @Test
        fun `block rule still wins over a chained allowed program`() {
            val matcher = CommandRuleMatcher(listOf(
                CommandRule("rm\\s+-rf", RuleAction.BLOCK, "Block rm -rf anywhere"),
                CommandRule("^git(\\s+.*)?$", RuleAction.ALLOW, "Allow git")
            ))
            assertEquals(RuleAction.BLOCK, matcher.match("git status; rm -rf /").action)
        }

        // Bare variable expansion ($HOME) is not command substitution and must stay ALLOW —
        // only `$(` opens a subshell. Guards against over-broad metacharacter matching.
        @Test
        fun `bare variable expansion stays allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ALLOW, matcher.match("echo \$HOME").action)
        }

        // Redirection lets a vetted read-only program WRITE: `cat notes.txt > build.gradle.kts`
        // matches the cat ALLOW rule yet overwrites an arbitrary sandbox file. A redirect must
        // therefore also hold the command back from auto-ALLOW (the user approves the full line).
        @Test
        fun `output redirection from allowed program is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("cat notes.txt > build.gradle.kts").action)
        }

        @Test
        fun `append redirection from allowed program is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("echo malicious >> settings.gradle.kts").action)
        }

        @Test
        fun `input redirection from allowed program is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("cat < .env").action)
        }
    }

    @Nested
    inner class MatchResultTests {
        @Test
        fun `should return matched rule with description`() {
            val matcher = CommandRuleMatcher(listOf(
                CommandRule("^git(\\s+.*)?$", RuleAction.ALLOW, "Git VCS")
            ))

            val result = matcher.match("git log")
            assertEquals("Git VCS", result.matchedRule?.description)
        }

        @Test
        fun `invalid regex fails at CommandRule construction not at match time`() {
            // Post Sprint 1: invalid regex is detected eagerly. Config loader must refuse
            // startup when this throws, instead of silently dropping the rule.
            val err = assertThrows<IllegalArgumentException> {
                CommandRule("[invalid", RuleAction.BLOCK, "Bad regex")
            }
            assert(err.message!!.contains("Invalid command rule regex")) { "unexpected: ${err.message}" }
        }
    }
}
