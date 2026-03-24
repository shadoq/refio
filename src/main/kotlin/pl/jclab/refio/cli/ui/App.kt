package pl.jclab.refio.cli.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import pl.jclab.refio.api.models.TaskMode
import java.nio.file.Path

fun launchComposeApp(projectPath: Path, mode: TaskMode, model: String?, noEgress: Boolean) {
    application {
        val viewModel = remember { RefioViewModel(projectPath, mode, model, noEgress) }

        LaunchedEffect(Unit) { viewModel.initialize() }

        Window(
            onCloseRequest = { viewModel.shutdown(); exitApplication() },
            title = "Refio — ${projectPath.toAbsolutePath().fileName}",
            state = rememberWindowState(width = 1200.dp, height = 800.dp)
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val isInitialized by viewModel.isInitialized.collectAsState()
                    val error by viewModel.error.collectAsState()

                    when {
                        error != null -> ErrorScreen(error!!)
                        !isInitialized -> LoadingScreen()
                        else -> MainContent(viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainContent(viewModel: RefioViewModel) {
    val messages by viewModel.messages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val agents by viewModel.agents.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val pendingApprovals by viewModel.pendingApprovals.collectAsState()
    val agentFilter by viewModel.agentFilter.collectAsState()

    // Filter messages by agent if filter is set
    val filteredMessages = if (agentFilter != null) {
        messages.filter { it.agentId == null || it.agentId == agentFilter }
    } else messages

    Row(Modifier.fillMaxSize()) {
        // 2/3 — Chat
        ChatPanel(
            messages = filteredMessages,
            isStreaming = isStreaming,
            onSend = { input -> viewModel.sendMessage(input) },
            modifier = Modifier.weight(2f)
        )

        VerticalDivider()

        // 1/3 — Status
        StatusPanel(
            agents = agents,
            metrics = metrics,
            pendingApprovals = pendingApprovals,
            onApprove = { id -> viewModel.approve(id) },
            onReject = { id -> viewModel.reject(id) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Initializing Refio...", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ErrorScreen(error: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Error", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
