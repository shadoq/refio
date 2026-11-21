package pl.jclab.refio.cli.tui.input

import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.state.TuiTab

/**
 * Slash commands that do what a key does, so `/model` and Ctrl+O are one action. A prompt template with the
 * same name wins: the command then expands the template as before.
 */
object SlashActions {

    data class Action(val name: String, val description: String, val shortcut: String, val action: TuiAction?)

    /** `/plan` switches straight to PLAN, unlike Shift+Tab which steps through the modes. */
    const val PLAN = "plan"

    val ALL: List<Action> = listOf(
        Action("continue", "Continue the conversation, or a stopped long task with its next round", "^L", TuiAction.ContinueConversation),
        Action("summarize", "Summarize the conversation", "^D", TuiAction.SummarizeConversation),
        Action("context", "Open the context panel", "F3", TuiAction.SwitchTab(TuiTab.CONTEXT)),
        Action(PLAN, "Switch to PLAN mode", "Shift+Tab", null),
        Action("model", "Choose the model", "^O", TuiAction.SelectModel),
        Action("new", "Start a new session", "^W", TuiAction.NewSession),
        Action("effort", "Next reasoning effort (off, low, medium, high)", "^T", TuiAction.ToggleThinking),
        Action("help", "Keys and commands", "F1", TuiAction.SwitchScreen(TuiScreen.HELP)),
    )

    /** The action [input] names, when it is exactly `/name` and no template is called that. */
    fun find(input: String, templateNames: Collection<String>): Action? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/") || trimmed.any { it.isWhitespace() }) return null
        val name = trimmed.removePrefix("/").lowercase()
        if (templateNames.any { it.equals(name, ignoreCase = true) }) return null
        return ALL.firstOrNull { it.name == name }
    }

    /** Candidates whose name starts with [typed] first, then those that only contain it. */
    fun filter(candidates: List<String>, typed: String): List<String> {
        val query = typed.lowercase()
        val prefix = candidates.filter { it.lowercase().startsWith(query) }
        val inside = candidates.filter { it !in prefix && it.lowercase().contains(query.removePrefix("/")) }
        return prefix + inside
    }
}
