package pl.jclab.refio.startup

import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import pl.jclab.refio.actions.RefioSlashPromptIntentionAction
import pl.jclab.refio.core.logging.dualLogger
import java.util.concurrent.atomic.AtomicBoolean

class RefioIntentionStartup : ProjectActivity {

    private val logger = dualLogger("RefioIntentionStartup")

    override suspend fun execute(project: Project) {
        if (registered.compareAndSet(false, true)) {
            IntentionManager.getInstance().addAction(RefioSlashPromptIntentionAction())
            logger.info { "[STARTUP] Registered Refio slash prompt intention" }
        } else {
            logger.debug { "[STARTUP] Refio slash prompt intention already registered" }
        }
    }

    companion object {
        private val registered = AtomicBoolean(false)
    }
}
