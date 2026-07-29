package pl.jclab.refio.ui

import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * A screen reachable from the navigation rail on the left side of the tool window.
 *
 * [advancedOnly] screens are hidden while the "Advanced View" setting is off, which mirrors
 * the behaviour the horizontal tab strip had before the rail replaced it.
 */
enum class RefioScreen(
    val title: String,
    val icon: Icon,
    val advancedOnly: Boolean = false
) {
    CHAT("Chat", AllIcons.Toolwindows.ToolWindowMessages),
    EXECUTION("Execution", AllIcons.Actions.ListFiles),
    CONTEXT("Context", AllIcons.FileTypes.Any_type, advancedOnly = true),
    AGENTS("Agents", AllIcons.Nodes.Related, advancedOnly = true),
    RAG("RAG", AllIcons.Nodes.DataSchema, advancedOnly = true),
    DEBUG("Debug", AllIcons.Actions.StartDebugger, advancedOnly = true),
    LOGS("Logs", AllIcons.Debugger.Console, advancedOnly = true),
    API("API", AllIcons.Nodes.Plugin, advancedOnly = true);

    companion object {

        /** Screens shown for the given "Advanced View" state, in rail order. */
        fun visibleFor(advancedView: Boolean): List<RefioScreen> =
            entries.filter { !it.advancedOnly || advancedView }
    }
}
