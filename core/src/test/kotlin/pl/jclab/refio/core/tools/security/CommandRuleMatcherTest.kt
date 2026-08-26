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

        // The payloads here are deliberately NOT on the BLOCK list (a destructive payload is
        // covered by SegmentedBlockTests instead), so what these assert is precisely the ALLOW
        // gate: a chained command never gets auto-approved on the strength of its leading program.

        @Test
        fun `chained command after allowed program is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("git status; python exfil.py").action)
        }

        @Test
        fun `and-chained command after allowed program is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("cat README.md && python exfil.py").action)
        }

        @Test
        fun `piped command from allowed program is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("git log | sh").action)
        }

        @Test
        fun `command substitution in allowed program is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("echo $(python exfil.py)").action)
        }

        @Test
        fun `backtick substitution in allowed program is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("echo `python exfil.py`").action)
        }

        @Test
        fun `newline-injected second command is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("git status\npython exfil.py").action)
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
    inner class SegmentedBlockTests {
        // Every BLOCK rule is anchored with `^` (`^rm\s+-r`), so matching it against the whole
        // command line only ever tests position 0: any prefix at all lifted the hard deny.
        // `rm -rf /` was blocked while `env rm -rf /`, `git status && rm -rf /` or
        // `find . -exec rm -rf {} +` ran. A hard deny must hold for every command the line
        // actually runs, so BLOCK rules are matched against each shell segment, each command
        // substitution, and the real command hidden behind a wrapper program.

        private val matcher = CommandRuleDefaults.createDefaultMatcher()

        @Test
        fun `blocks a destructive command chained after an allowed program`() {
            assertEquals(RuleAction.BLOCK, matcher.match("cat README.md && rm -rf /").action)
        }

        @Test
        fun `blocks a destructive command after a semicolon`() {
            assertEquals(RuleAction.BLOCK, matcher.match("git status; rm -rf /").action)
        }

        @Test
        fun `blocks a destructive command on a second line`() {
            assertEquals(RuleAction.BLOCK, matcher.match("git status\nrm -rf /").action)
        }

        @Test
        fun `blocks a destructive command inside command substitution`() {
            assertEquals(RuleAction.BLOCK, matcher.match("echo $(git reset --hard HEAD~5)").action)
        }

        @Test
        fun `blocks a destructive command inside backtick substitution`() {
            assertEquals(RuleAction.BLOCK, matcher.match("echo `rm -rf /`").action)
        }

        @Test
        fun `blocks a destructive command run through env`() {
            assertEquals(RuleAction.BLOCK, matcher.match("env rm -rf /").action)
        }

        @Test
        fun `blocks a destructive command run through env with an assignment`() {
            assertEquals(RuleAction.BLOCK, matcher.match("env FOO=1 rm -rf /tmp/x").action)
        }

        @Test
        fun `blocks a destructive command run through timeout`() {
            assertEquals(RuleAction.BLOCK, matcher.match("timeout 5 git push --force origin main").action)
        }

        @Test
        fun `blocks a destructive command run through nohup`() {
            assertEquals(RuleAction.BLOCK, matcher.match("nohup rm -rf /var/data").action)
        }

        @Test
        fun `blocks a destructive command piped into xargs`() {
            assertEquals(RuleAction.BLOCK, matcher.match("find . -name '*.log' | xargs rm -f").action)
        }

        @Test
        fun `blocks a destructive command passed to a shell with -c`() {
            assertEquals(RuleAction.BLOCK, matcher.match("sh -c \"rm -rf /\"").action)
        }

        @Test
        fun `blocks a destructive command passed to bash -c in single quotes`() {
            assertEquals(RuleAction.BLOCK, matcher.match("bash -c 'echo hi; rm -rf /'").action)
        }

        @Test
        fun `blocks find -exec of a destructive command`() {
            assertEquals(RuleAction.BLOCK, matcher.match("find . -name '*.log' -exec rm -rf {} +").action)
        }

        @Test
        fun `blocks a destructive command hidden in a subshell`() {
            assertEquals(RuleAction.BLOCK, matcher.match("(cd /tmp && rm -rf data)").action)
        }

        @Test
        fun `blocks a destructive command in a loop body`() {
            assertEquals(RuleAction.BLOCK, matcher.match("for d in a b; do rm -rf \$d; done").action)
        }

        // Splitting must not turn ordinary arguments into commands: a destructive-looking
        // string inside quotes is data, not a second command, and blocking it would break
        // everyday work (writing about `rm -rf`, grepping for it).
        @Test
        fun `does not block a destructive-looking phrase inside a quoted argument`() {
            assertEquals(RuleAction.ALLOW, matcher.match("git commit -m \"drop the rm -rf helper\"").action)
        }

        @Test
        fun `does not block a search for a destructive pattern`() {
            assertEquals(RuleAction.ALLOW, matcher.match("grep -rn 'rm -rf' .").action)
        }

        @Test
        fun `does not block an escaped separator inside an argument`() {
            assertEquals(RuleAction.ALLOW, matcher.match("grep -rn rm\\ -rf src").action)
        }

        // Writing a script is not running it. A here-document body is text handed to the command,
        // so a deploy script that happens to contain `rm -rf build` must still be writable - and a
        // redirected string is data for the same reason.
        @Test
        fun `does not block a destructive line inside a here-document body`() {
            val command = "cat > deploy.sh <<'EOF'\n#!/bin/sh\nrm -rf build\nEOF"
            assertEquals(RuleAction.ASK, matcher.match(command).action)
        }

        @Test
        fun `does not block a destructive line inside an unquoted here-document body`() {
            val command = "cat > deploy.sh <<EOF\nrm -rf build\nEOF"
            assertEquals(RuleAction.ASK, matcher.match(command).action)
        }

        @Test
        fun `does not block a destructive-looking string being written to a file`() {
            assertEquals(RuleAction.ASK, matcher.match("echo \"rm -rf /\" > notes.txt").action)
        }

        // Skipping the body must not skip the line that opened it, nor the commands after it.
        @Test
        fun `blocks a destructive command chained on the line that opens a here-document`() {
            assertEquals(RuleAction.BLOCK, matcher.match("cat <<EOF; rm -rf /\nbody\nEOF").action)
        }

        @Test
        fun `blocks a destructive command that follows the here-document terminator`() {
            val command = "cat > deploy.sh <<'EOF'\nrm -rf build\nEOF\nrm -rf /"
            assertEquals(RuleAction.BLOCK, matcher.match(command).action)
        }

        // `find -delete` is mass deletion, but it is also everyday cleanup, and a hard deny leaves
        // no way to approve it knowingly. It asks instead - `find` is off the allow list, so the
        // user sees the whole line including `-delete`.
        @Test
        fun `find -delete asks rather than blocking`() {
            assertEquals(RuleAction.ASK, matcher.match("find . -name '*.tmp' -delete").action)
        }
    }

    @Nested
    inner class WrapperProgramAllowTests {
        // `env` and `find` were on the ALLOW list, yet both launch other programs
        // (`env <cmd>`, `find -exec`), so vetting the leading program proved nothing.

        @Test
        fun `env with a payload command is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("env python exfil.py").action)
        }

        @Test
        fun `bare env stays allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ALLOW, matcher.match("env").action)
        }

        @Test
        fun `find is not auto-allowed`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ASK, matcher.match("find . -name '*.kt'").action)
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
