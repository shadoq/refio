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
    }

    @Nested
    inner class AllowTests {
        @Test
        fun `should allow git status`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ALLOW, matcher.match("git status").action)
        }

        @Test
        fun `should allow npm test`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ALLOW, matcher.match("npm test").action)
        }

        @Test
        fun `should allow ls`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ALLOW, matcher.match("ls -la").action)
        }

        @Test
        fun `should allow gradlew`() {
            val matcher = CommandRuleDefaults.createDefaultMatcher()
            assertEquals(RuleAction.ALLOW, matcher.match("gradlew build").action)
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
