package pl.jclab.refio.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.UIAdapter
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Wires up every collaborator needed by [CoreSessionService] so embedders (CLI, tests) don't
 * have to duplicate the 7-class assembly that `SessionManager` does on the IntelliJ side.
 *
 * The plugin keeps its own wiring (it needs IntelliJ-specific adapters). Everyone else can
 * call [create] with minimal arguments and get a ready-to-use [CoreSessionService].
 */
object CoreSessionServiceFactory {

    fun create(
        projectRouter: CoreApiRouter,
        projectId: String,
        projectPath: Path,
        scope: CoroutineScope,
        stateManager: SessionStateManager = SessionStateManager(),
        vfsRefresher: VfsRefresher = VfsRefresher.NoOp,
        uiAdapter: UIAdapter = NoopUIAdapter,
        executionStateController: ExecutionStateController = NoopExecutionStateController,
    ): CoreSessionService {
        val modeSwitchMutex = Mutex()

        val messageDispatcher = MessageDispatcher(
            projectRouter = projectRouter,
            stateManager = stateManager,
        )

        lateinit var subtaskTrackerRef: SubtaskTracker
        lateinit var executionMonitorRef: ExecutionMonitor

        val executionMonitor = ExecutionMonitor(
            projectRouter = projectRouter,
            stateManager = stateManager,
            stepExecutionService = executionStateController,
            scope = scope,
            loadMessages = { messageDispatcher.loadMessages() },
            loadSubtasks = { subtaskTrackerRef.loadSubtasks() },
            prepareNextStep = { subtaskTrackerRef.prepareNextStep() },
        )
        executionMonitorRef = executionMonitor

        val subtaskTracker = SubtaskTracker(
            projectRouter = projectRouter,
            stateManager = stateManager,
            vfsRefresher = vfsRefresher,
            loadMessages = { messageDispatcher.loadMessages() },
            executeCurrentStep = { subtaskId -> executionMonitorRef.executeCurrentStep(subtaskId) },
            showApprovalMessageForNextSubtask = { executionMonitorRef.showApprovalMessageForNextSubtask() },
        )
        subtaskTrackerRef = subtaskTracker

        val lifecycleService = SessionLifecycleService(
            projectRouter = projectRouter,
            configService = projectRouter.configService,
            stateManager = stateManager,
            modeSwitchMutex = modeSwitchMutex,
            projectId = projectId,
            normalizedProjectPath = projectPath.toAbsolutePath().normalize().toString(),
            scope = scope,
        )
        lifecycleService.initialize(messageDispatcher, subtaskTracker, executionMonitor)

        return CoreSessionService(
            projectRouter = projectRouter,
            stateManager = stateManager,
            subtaskTracker = subtaskTracker,
            messageDispatcher = messageDispatcher,
            lifecycleService = lifecycleService,
            uiAdapter = uiAdapter,
            scope = scope,
            modeSwitchMutex = modeSwitchMutex,
        )
    }

    object NoopExecutionStateController : ExecutionStateController {
        override fun startInteractiveExecution(taskId: String) = Unit
        override fun stopExecution() = Unit
        override fun markComplete() = Unit
    }

    object NoopUIAdapter : UIAdapter {
        override fun showMessage(message: String) = Unit
        override fun showError(error: String) = Unit
        override fun updateStatus(status: String) = Unit
        override fun showProgress(title: String, fraction: Double) = Unit
        override fun askQuestion(question: String): CompletableFuture<String> =
            CompletableFuture.completedFuture("")
        override fun log(level: String, message: String) = Unit
    }
}
