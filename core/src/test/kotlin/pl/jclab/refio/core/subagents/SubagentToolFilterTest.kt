package pl.jclab.refio.core.subagents

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract for the subagent tool gate after the 2026-07 fix: safe internal tools
 * (think/tasks/memory) are always available under a `tools:` whitelist, egress tools never are.
 *
 * The bug this pins: a persona body that instructs `think(...)` while the whitelist omits it made
 * the model loop on a tool the harness rejected ("Tool 'think' is not available to the subagent…").
 * [SubagentToolFilter.isToolAllowedUnderProfile] is the single source of truth the three
 * turn-package `isToolAllowedByProfile` copies delegate to, so the system prompt, JSON validation,
 * and execution can never disagree about what a subagent may call.
 */
class SubagentToolFilterTest {

    @Test
    fun `SYSTEM_TOOLS holds the safe internal set and never an egress tool`() {
        // These have no side effects and no network egress — safe to always grant.
        assertTrue("think" in SubagentToolFilter.SYSTEM_TOOLS)
        assertTrue("tasks" in SubagentToolFilter.SYSTEM_TOOLS)
        assertTrue("memory" in SubagentToolFilter.SYSTEM_TOOLS)
        // Egress / shell must be opt-in via an explicit whitelist, NOT auto-granted.
        assertFalse("web_search" in SubagentToolFilter.SYSTEM_TOOLS)
        assertFalse("http_request" in SubagentToolFilter.SYSTEM_TOOLS)
        assertFalse("fetch_webpage" in SubagentToolFilter.SYSTEM_TOOLS)
        assertFalse("run_terminal_command" in SubagentToolFilter.SYSTEM_TOOLS)
    }

    @Test
    fun `a whitelist that omits think still allows think, tasks and memory`() {
        // security-engineer's exact whitelist — no think, yet its persona instructs it.
        val allowed = listOf("read_file", "grep_search", "file_search", "read_directory", "view_diff")
        assertTrue(SubagentToolFilter.isToolAllowedUnderProfile("read_file", allowed, null))
        assertTrue(SubagentToolFilter.isToolAllowedUnderProfile("think", allowed, null))
        assertTrue(SubagentToolFilter.isToolAllowedUnderProfile("tasks", allowed, null))
        assertTrue(SubagentToolFilter.isToolAllowedUnderProfile("memory", allowed, null))
    }

    @Test
    fun `a whitelist never auto-grants an egress tool it did not list`() {
        val allowed = listOf("read_file", "grep_search")
        assertFalse(SubagentToolFilter.isToolAllowedUnderProfile("web_search", allowed, null))
        assertFalse(SubagentToolFilter.isToolAllowedUnderProfile("http_request", allowed, null))
        assertFalse(SubagentToolFilter.isToolAllowedUnderProfile("run_terminal_command", allowed, null))
    }

    @Test
    fun `the check is case-insensitive for both the name and the whitelist`() {
        val allowed = listOf("Read_File")
        assertTrue(SubagentToolFilter.isToolAllowedUnderProfile("READ_FILE", allowed, null))
        assertTrue(SubagentToolFilter.isToolAllowedUnderProfile("Think", allowed, null))
    }

    @Test
    fun `blacklist mode allows anything not explicitly denied, including think`() {
        val disallowed = listOf("web_search")
        assertFalse(SubagentToolFilter.isToolAllowedUnderProfile("web_search", null, disallowed))
        assertTrue(SubagentToolFilter.isToolAllowedUnderProfile("think", null, disallowed))
        assertTrue(SubagentToolFilter.isToolAllowedUnderProfile("read_file", null, disallowed))
    }

    @Test
    fun `no whitelist and no blacklist allows everything (inherit)`() {
        assertTrue(SubagentToolFilter.isToolAllowedUnderProfile("anything_at_all", null, null))
    }
}
