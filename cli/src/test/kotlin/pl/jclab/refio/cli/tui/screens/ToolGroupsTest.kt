package pl.jclab.refio.cli.tui.screens

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.input.TuiAction
import pl.jclab.refio.cli.tui.input.TuiInputHandler
import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiViewModel
import pl.jclab.refio.core.api.ToolDefinitionInfo
import pl.jclab.refio.core.models.api.ToolPermissionDto
import kotlin.test.assertEquals

class ToolGroupsTest {

    private fun def(name: String, category: String) = ToolDefinitionInfo(name, "$name does things", "READ_ONLY", category, "ON", "ON")
    private fun perm(name: String) = ToolPermissionDto(name, "ON", "ASK")

    private val permissions = listOf("run_terminal_command", "read_file", "grep_search", "code_editing", "mystery_tool", "create_new_file").map(::perm)
    private val definitions = mapOf(
        "run_terminal_command" to def("run_terminal_command", "EXECUTION"),
        "read_file" to def("read_file", "DATA_PRODUCING"),
        "grep_search" to def("grep_search", "DATA_PRODUCING"),
        "code_editing" to def("code_editing", "FILE_MODIFYING"),
        "create_new_file" to def("create_new_file", "FILE_PRODUCING"),
    ).toMutableMap().also { it["mystery_tool"] = def("mystery_tool", "SOMETHING_NEW") }

    @Test
    fun `tools are grouped by their core category in enum order`() {
        val groups = ToolGroups.group(permissions, definitions)
        assertEquals(listOf("Data producing", "File modifying", "File producing", "Execution", "Other"), groups.map { it.label })
        assertEquals(listOf("grep_search", "read_file"), groups.first().tools.map { it.toolName })
        assertEquals(listOf("mystery_tool"), groups.last().tools.map { it.toolName })
    }

    private val viewModel = mockk<TuiViewModel>(relaxed = true)
    private val handler = TuiInputHandler(Terminal(terminalInterface = TerminalRecorder()))

    @AfterEach
    fun detach() {
        TuiSettingsScreen.setViewModel(mockk(relaxed = true))
    }

    private fun onToolsTab(selectedField: Int) {
        every { viewModel.getToolPermissions() } returns permissions
        every { viewModel.getToolDefinitions() } returns definitions
        every { viewModel.getConfigSection(any()) } returns emptyMap()
        TuiSettingsScreen.setViewModel(viewModel)
        every { viewModel.stateFlow } returns MutableStateFlow(TuiState(screen = TuiScreen.SETTINGS, settingsTab = 7, settingsSelectedField = selectedField))
    }

    @Test
    fun `a k x change only the focused group in the focused mode column`() {
        // Fields: grep_search plan/agent (0,1), read_file plan/agent (2,3), code_editing (4,5), ...
        onToolsTab(selectedField = 3)
        handler.dispatchAction(TuiAction.TypeChar('k'), viewModel)
        verify { viewModel.setToolGroupPermission(listOf("grep_search", "read_file"), true, "ASK") }

        onToolsTab(selectedField = 4)
        handler.dispatchAction(TuiAction.TypeChar('x'), viewModel)
        verify { viewModel.setToolGroupPermission(listOf("code_editing"), false, "OFF") }
    }

    @Test
    fun `tab jumps to the first field of the next group and wraps`() {
        onToolsTab(selectedField = 1)
        handler.dispatchAction(TuiAction.TogglePanelFocus, viewModel)
        verify { viewModel.settingsSelectField(4) }

        onToolsTab(selectedField = 11) // mystery_tool agent, the last group
        handler.dispatchAction(TuiAction.TogglePanelFocus, viewModel)
        verify { viewModel.settingsSelectField(0) }
    }
}
