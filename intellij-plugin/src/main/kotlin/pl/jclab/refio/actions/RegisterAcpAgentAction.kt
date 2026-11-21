package pl.jclab.refio.actions

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.services.acp.AcpRegistration
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Registers the installed Refio CLI as an agent in JetBrains AI Assistant, so Refio can be picked
 * in AI Chat. Asks for the CLI launcher path (remembered for next time) and writes the entry.
 */
class RegisterAcpAgentAction : AnAction(
    "Register Refio in AI Assistant",
    "Add the Refio CLI to the agent list of JetBrains AI Assistant (~/.jetbrains/acp.json)",
    null
) {
    private val logger = dualLogger("RegisterAcpAgentAction")

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val properties = PropertiesComponent.getInstance()
        val remembered = properties.getValue(CLI_PATH_KEY).orEmpty()

        val cliPath = Messages.showInputDialog(
            project,
            "Full path to the Refio CLI launcher (refio.bat on Windows, cli/build/install/cli/bin/cli elsewhere):",
            TITLE,
            null,
            remembered,
            object : InputValidator {
                override fun checkInput(inputString: String?): Boolean = isExistingFile(inputString)
                override fun canClose(inputString: String?): Boolean = isExistingFile(inputString)
            }
        )?.trim() ?: return
        properties.setValue(CLI_PATH_KEY, cliPath)

        val configPath = AcpRegistration.defaultConfigPath()
        val result = try {
            AcpRegistration.register(configPath, Paths.get(cliPath).toAbsolutePath().normalize().toString())
        } catch (ex: Exception) {
            logger.error(ex) { "Failed to write $configPath" }
            Messages.showErrorDialog(project, "Could not write $configPath: ${ex.message}", TITLE)
            return
        }
        when (result) {
            is AcpRegistration.Result.Written -> Messages.showInfoMessage(
                project,
                "Refio was added to $configPath" +
                    (if (result.created) "." else " (previous version saved as acp.json.bak).") +
                    "\nPick \"Refio\" in the agent list of AI Chat. If it does not show up, restart the IDE.",
                TITLE
            )
            is AcpRegistration.Result.Unchanged -> Messages.showInfoMessage(
                project, "Refio is already registered in $configPath.", TITLE
            )
            is AcpRegistration.Result.Invalid -> Messages.showErrorDialog(
                project, "$configPath was not changed: ${result.reason}. Fix the file or remove it and try again.", TITLE
            )
        }
    }

    private fun isExistingFile(path: String?): Boolean =
        !path.isNullOrBlank() && runCatching { Files.isRegularFile(Paths.get(path.trim())) }.getOrDefault(false)

    private companion object {
        const val TITLE = "Register Refio in AI Assistant"
        const val CLI_PATH_KEY = "refio.acp.cliPath"
    }
}
