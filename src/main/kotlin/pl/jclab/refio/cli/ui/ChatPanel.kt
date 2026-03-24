package pl.jclab.refio.cli.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun ChatPanel(
    messages: List<UIChatMessage>,
    isStreaming: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxHeight().padding(8.dp)) {
        val listState = rememberLazyListState()

        // Auto-scroll to bottom on new messages
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        // Message list
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }
            if (isStreaming) {
                item {
                    StreamingIndicator()
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        // Input area
        var input by remember { mutableStateOf("") }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && !event.isShiftPressed && event.type == KeyEventType.KeyDown) {
                            if (input.isNotBlank() && !isStreaming) {
                                onSend(input.trim())
                                input = ""
                            }
                            true
                        } else false
                    },
                placeholder = { Text("Type a message... (Enter to send, Shift+Enter for newline)") },
                maxLines = 5,
                enabled = !isStreaming
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        onSend(input.trim())
                        input = ""
                    }
                },
                enabled = !isStreaming && input.isNotBlank()
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
fun MessageBubble(msg: UIChatMessage) {
    val bgColor = when {
        msg.agentColor != null -> msg.agentColor.copy(alpha = 0.1f)
        msg.role == "user" -> MaterialTheme.colorScheme.primaryContainer
        msg.role == "system" -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(Modifier.padding(12.dp)) {
            // Header: role/agent name + timestamp
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = msg.agentName ?: msg.role.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                    color = msg.agentColor ?: MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatTime(msg.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))

            // Content
            SelectionContainer {
                Text(
                    text = msg.content,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Streaming indicator
            if (msg.isStreaming) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun StreamingIndicator() {
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("Thinking...", style = MaterialTheme.typography.bodySmall)
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss")

fun formatTime(timestamp: Long): String {
    return timeFormat.format(Date(timestamp))
}
