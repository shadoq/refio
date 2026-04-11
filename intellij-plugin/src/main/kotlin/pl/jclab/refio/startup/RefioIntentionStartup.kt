package pl.jclab.refio.startup

import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import pl.jclab.refio.actions.RefioSlashCommandIntentionAction
import pl.jclab.refio.services.logging.dualLogger
import java.util.concurrent.atomic.AtomicBoolean

class RefioIntentionStartup : ProjectActivity {

    private val logger = dualLogger("RefioIntentionStartup")

    override suspend fun execute(project: Project) {
        if (registered.compareAndSet(false, true)) {
            IntentionManager.getInstance().addAction(RefioSlashCommandIntentionAction())
            logger.info { "[STARTUP] Registered Refio slash command intention" }
        } else {
            logger.debug { "[STARTUP] Refio slash command intention already registered" }
        }
    }

    companion object {
        private val registered = AtomicBoolean(false)
    }
}
