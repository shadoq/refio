package pl.jclab.refio.startup

import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import pl.jclab.refio.actions.RefioSlashCommandIntentionAction
import pl.jclab.refio.api.models.SlashCommand
import pl.jclab.refio.services.logging.dualLogger

class RefioIntentionStartup : ProjectActivity {

    private val logger = dualLogger("RefioIntentionStartup")

    override suspend fun execute(project: Project) {
        val commands = SlashCommand.BUILTINS.filter { it.showInEditor }
        val intentionManager = IntentionManager.getInstance()

        for (command in commands) {
            intentionManager.addAction(RefioSlashCommandIntentionAction(command))
        }

        logger.info { "[STARTUP] Registered ${commands.size} Refio intention actions: ${commands.map { it.name }}" }
    }
}
