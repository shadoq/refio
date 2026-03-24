package pl.jclab.refio.cli.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun StatusPanel(
    agents: List<AgentState>,
    metrics: MetricsInfo,
    pendingApprovals: List<PendingApproval> = emptyList(),
    onApprove: (String) -> Unit = {},
    onReject: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxHeight().padding(8.dp)) {
        // Section 1: Agent Flow (DAG)
        Text("Agents", style = MaterialTheme.typography.titleSmall)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        if (agents.isEmpty()) {
            Text(
                "No active agents",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            AgentFlowPanel(agents, Modifier.weight(1f))
        }

        // Section 2: Approvals
        if (pendingApprovals.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Approvals (${pendingApprovals.size})",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFFFF9800)
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ApprovalPanel(pendingApprovals, onApprove, onReject, Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))

        // Section 3: Metrics
        MetricsCard(metrics)
    }
}

@Composable
fun AgentFlowPanel(agents: List<AgentState>, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(agents, key = { it.id }) { agent ->
            AgentNode(agent)
            if (agent.dependsOn.isNotEmpty()) {
                val depNames = agent.dependsOn.joinToString()
                Text(
                    "  ^ depends on: $depNames",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun AgentNode(agent: AgentState) {
    val (statusIcon, statusColor) = when (agent.status) {
        "RUNNING" -> ">" to Color(0xFF4CAF50)
        "COMPLETED" -> "+" to Color(0xFF2196F3)
        "FAILED" -> "x" to Color(0xFFF44336)
        "WAITING_APPROVAL" -> "?" to Color(0xFFFF9800)
        "WAITING_DATA" -> "~" to Color(0xFFFFEB3B)
        "PENDING" -> "." to Color.Gray
        else -> "o" to Color.Gray
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.1f)
        )
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "[$statusIcon]",
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(agent.name, fontWeight = FontWeight.Bold)
                    if (agent.currentPhase != null) {
                        Text(
                            agent.currentPhase,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (agent.costUsd > 0) {
                    Text(
                        "$${String.format("%.3f", agent.costUsd)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (agent.status == "RUNNING") {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}

@Composable
fun ApprovalPanel(
    approvals: List<PendingApproval>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    modifier: Modifier
) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(approvals, key = { it.id }) { approval ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFF9800).copy(alpha = 0.1f)
                )
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        "${approval.agentName}: ${approval.action}",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Risk: ${approval.risk}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800)
                    )
                    approval.details.forEach { (k, v) ->
                        Text("$k: $v", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.padding(top = 4.dp)) {
                        Button(
                            onClick = { onApprove(approval.id) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4CAF50)
                            )
                        ) { Text("Approve") }
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(
                            onClick = { onReject(approval.id) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Reject") }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricsCard(metrics: MetricsInfo) {
    Text("Metrics", style = MaterialTheme.typography.titleSmall)
    HorizontalDivider(Modifier.padding(vertical = 4.dp))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            MetricRow("Tokens In", metrics.tokensIn.toString())
            MetricRow("Tokens Out", metrics.tokensOut.toString())
            MetricRow("Cost", "$${String.format("%.4f", metrics.costUsd)}")
            if (metrics.totalAgents > 0) {
                MetricRow("Agents", "${metrics.completedAgents}/${metrics.totalAgents}")
            }
            if (metrics.totalDurationMs > 0) {
                MetricRow("Duration", "${metrics.totalDurationMs / 1000}s")
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
