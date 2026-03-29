package pl.jclab.refio.core.db.migrations

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("SeedTestDataMigration")

/**
 * Migration version 1: Seeds test data for UI development
 *
 * Creates various conversation scenarios:
 * - Simple CHAT conversations
 * - AGENT mode with tool calls
 * - PLAN mode with subtasks
 * - Multi-turn conversations with errors
 * - Long conversations with context
 */
class SeedTestDataMigration : Migration {
    override val version: Int = 1

    override fun migrate(database: Database) {
        transaction(database) {
            logger.info { "Seeding test data for UI development..." }

            // Use legacy_unknown as default projectId (matches TasksTable default)
            // This ensures data is visible in HistoryPanel for any project
            val demoProjectId = "legacy_unknown"
            val demoProjectPath = "/Users/demo/refio"

            // ========================================
            // SCENARIO 1: Simple CHAT conversation (Q&A)
            // ========================================
            val chatTaskId = createTask(
                name = "Kotlin Coroutines Question",
                mode = TaskMode.CHAT,
                status = TaskStatus.SUCCESS,
                projectId = demoProjectId,
                projectPath = demoProjectPath,
                tokensIn = 850,
                tokensOut = 420,
                costUsd = 0.0018
            )

            createMessage(
                taskId = chatTaskId,
                role = MessageRole.USER,
                content = "How do Kotlin coroutines work? Can you explain what a suspend function is?",
                createdAt = System.currentTimeMillis() - 300_000
            )

            createMessage(
                taskId = chatTaskId,
                role = MessageRole.ASSISTANT,
                content = """## Kotlin Coroutines Overview

**Coroutine** is a concurrency mechanism that allows writing asynchronous code in a sequential style.

### Suspend Function

A `suspend` function can be paused and resumed without blocking a thread:

```kotlin
suspend fun fetchData(): User {
    delay(1000) // suspends coroutine, not thread
    return User("John", "Doe")
}
```

**Key characteristics:**
- Can only be called from another suspend function or coroutine
- Doesn't block thread while waiting
- Allows handling millions of concurrent operations

### Core components:
- **Dispatchers** - determine where code executes (IO, Main, Default)
- **Scope** - manages coroutine lifecycle
- **Job** - represents an executing operation""",
                tokensIn = 850,
                tokensOut = 420,
                cost = 0.0018,
                createdAt = System.currentTimeMillis() - 290_000
            )

            // ========================================
            // SCENARIO 2: AGENT mode with tool calls (file search + read)
            // ========================================
            val agentTaskId = createTask(
                name = "Extract function to separate file",
                mode = TaskMode.AGENT,
                status = TaskStatus.RUNNING,
                executionMode = ExecutionMode.INTERACTIVE,
                projectId = demoProjectId,
                projectPath = demoProjectPath,
                tokensIn = 2450,
                tokensOut = 890,
                costUsd = 0.0067
            )

            createMessage(
                taskId = agentTaskId,
                role = MessageRole.USER,
                content = "Extract the `calculateTotal` function from UserService.kt into a separate CalcUtils.kt file",
                createdAt = System.currentTimeMillis() - 600_000
            )

            // Assistant calls tool
            val toolCallId1 = "call_abc123"
            createMessage(
                taskId = agentTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCallData(
                        id = toolCallId1,
                        name = "file_search",
                        arguments = """{"pattern":"UserService.kt","path":"src/main/kotlin"}"""
                    )
                ),
                createdAt = System.currentTimeMillis() - 590_000
            )

            // Tool result
            createMessage(
                taskId = agentTaskId,
                role = MessageRole.TOOL,
                content = """Found 1 file:
- src/main/kotlin/com/example/service/UserService.kt""",
                toolCallId = toolCallId1,
                createdAt = System.currentTimeMillis() - 580_000
            )

            // Assistant calls read_file
            val toolCallId2 = "call_def456"
            createMessage(
                taskId = agentTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCallData(
                        id = toolCallId2,
                        name = "read_file",
                        arguments = """{"path":"src/main/kotlin/com/example/service/UserService.kt"}"""
                    )
                ),
                createdAt = System.currentTimeMillis() - 570_000
            )

            // Tool result with file content
            createMessage(
                taskId = agentTaskId,
                role = MessageRole.TOOL,
                content = """```kotlin
package com.example.service

class UserService {
    fun calculateTotal(prices: List<Double>): Double {
        return prices.sum()
    }

    fun getUser(id: String): User {
        // implementation
    }
}
```""",
                toolCallId = toolCallId2,
                createdAt = System.currentTimeMillis() - 560_000
            )

            // Assistant response with explanation
            createMessage(
                taskId = agentTaskId,
                role = MessageRole.ASSISTANT,
                content = """Found the `calculateTotal` function in UserService.kt. Proceeding with extraction:

1. Create new file `CalcUtils.kt`
2. Move `calculateTotal` function
3. Update imports in UserService.kt""",
                createdAt = System.currentTimeMillis() - 550_000
            )

            // Create file tool call
            val toolCallId3 = "call_ghi789"
            createMessage(
                taskId = agentTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCallData(
                        id = toolCallId3,
                        name = "create_new_file",
                        arguments = """{"path":"src/main/kotlin/com/example/utils/CalcUtils.kt","content":"package com.example.utils\n\nfun calculateTotal(prices: List<Double>): Double {\n    return prices.sum()\n}"}"""
                    )
                ),
                createdAt = System.currentTimeMillis() - 540_000
            )

            createMessage(
                taskId = agentTaskId,
                role = MessageRole.TOOL,
                content = "File created successfully: src/main/kotlin/com/example/utils/CalcUtils.kt",
                toolCallId = toolCallId3,
                createdAt = System.currentTimeMillis() - 530_000
            )

            // Update original file
            val toolCallId4 = "call_jkl012"
            createMessage(
                taskId = agentTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCallData(
                        id = toolCallId4,
                        name = "code_editing",
                        arguments = """{"path":"src/main/kotlin/com/example/service/UserService.kt","old_string":"class UserService {\n    fun calculateTotal(prices: List<Double>): Double {\n        return prices.sum()\n    }","new_string":"import com.example.utils.calculateTotal\n\nclass UserService {"}"""
                    )
                ),
                createdAt = System.currentTimeMillis() - 520_000
            )

            createMessage(
                taskId = agentTaskId,
                role = MessageRole.TOOL,
                content = "File updated successfully",
                toolCallId = toolCallId4,
                createdAt = System.currentTimeMillis() - 510_000
            )

            // Final assistant message
            createMessage(
                taskId = agentTaskId,
                role = MessageRole.ASSISTANT,
                content = """✅ **Complete**

Extracted `calculateTotal` function to separate file:

**Created:** `src/main/kotlin/com/example/utils/CalcUtils.kt`
**Modified:** `src/main/kotlin/com/example/service/UserService.kt`

Function is now available as `com.example.utils.calculateTotal()` and can be used across the project.""",
                createdAt = System.currentTimeMillis() - 500_000
            )

            // ========================================
            // SCENARIO 3: PLAN mode with subtasks
            // ========================================
            val planTaskId = createTask(
                name = "Refactoring: Add email validation",
                mode = TaskMode.PLAN,
                status = TaskStatus.PLANNED,
                executionMode = ExecutionMode.INTERACTIVE,
                requiresPlanApproval = true,
                planApproved = false,
                sourcePlanId = "plan_email_validation",
                planVersion = 1,
                projectId = demoProjectId,
                projectPath = demoProjectPath,
                tokensIn = 3200,
                tokensOut = 1450,
                costUsd = 0.0093
            )

            createMessage(
                taskId = planTaskId,
                role = MessageRole.USER,
                content = "I want to add email validation to the registration form. Currently users can enter any string.",
                createdAt = System.currentTimeMillis() - 900_000
            )

            createMessage(
                taskId = planTaskId,
                role = MessageRole.ASSISTANT,
                content = """# Plan: Email Validation

I'll analyze the code and prepare a plan for implementing email validation.""",
                createdAt = System.currentTimeMillis() - 890_000
            )

            // Create subtasks for PLAN mode
            createSubtask(
                taskId = planTaskId,
                orderIndex = 1,
                kind = SubtaskKind.FILE_SEARCH,
                status = TaskStatus.SUCCESS,
                description = "Find registration-related files",
                paramsJson = """{"pattern":"*Register*.kt"}""",
                summary = "Found RegistrationForm.kt and UserService.kt",
                llmModel = "claude-sonnet-4-5",
                llmProvider = "anthropic",
                inputTokens = 450,
                outputTokens = 120,
                costUsd = 0.0015
            )

            createSubtask(
                taskId = planTaskId,
                orderIndex = 2,
                kind = SubtaskKind.READ_FILE,
                status = TaskStatus.SUCCESS,
                description = "Read RegistrationForm.kt to understand structure",
                paramsJson = """{"path":"src/main/kotlin/com/example/ui/RegistrationForm.kt"}""",
                summary = "Form uses TextField for email without validation",
                llmModel = "claude-sonnet-4-5",
                llmProvider = "anthropic",
                inputTokens = 890,
                outputTokens = 340,
                costUsd = 0.0037
            )

            createSubtask(
                taskId = planTaskId,
                orderIndex = 3,
                kind = SubtaskKind.PROJECT_ANALYSIS,
                status = TaskStatus.PLANNED,
                description = "Analyze existing validation in project",
                paramsJson = """{"scope":"validation"}""",
                summary = null,
                llmModel = null,
                llmProvider = null
            )

            createSubtask(
                taskId = planTaskId,
                orderIndex = 4,
                kind = SubtaskKind.CREATE_NEW_FILE,
                status = TaskStatus.NEW,
                description = "Create EmailValidator.kt with regex validation",
                paramsJson = """{"path":"src/main/kotlin/com/example/validation/EmailValidator.kt"}""",
                stepPlanJson = """{"plan":"Create singleton with isValidEmail() function using regex"}""",
                summary = null
            )

            createSubtask(
                taskId = planTaskId,
                orderIndex = 5,
                kind = SubtaskKind.CODE_EDITING,
                status = TaskStatus.NEW,
                description = "Integrate EmailValidator with RegistrationForm",
                paramsJson = """{"path":"src/main/kotlin/com/example/ui/RegistrationForm.kt"}""",
                summary = null
            )

            createMessage(
                taskId = planTaskId,
                role = MessageRole.ASSISTANT,
                content = """## Plan prepared

Prepared 5 steps for email validation implementation:

1. ✅ **FILE_SEARCH** - Found RegistrationForm.kt
2. ✅ **READ_FILE** - Read form structure
3. 📋 **PROJECT_ANALYSIS** - Analyze existing validation
4. 🆕 **CREATE_NEW_FILE** - Create EmailValidator.kt
5. 🆕 **CODE_EDITING** - Integrate with form

Do you approve this plan for execution?""",
                createdAt = System.currentTimeMillis() - 800_000
            )

            // ========================================
            // SCENARIO 4: Multi-turn conversation with error and retry
            // ========================================
            val errorTaskId = createTask(
                name = "Debug: NullPointerException",
                mode = TaskMode.CHAT,
                status = TaskStatus.RUNNING,
                projectId = demoProjectId,
                projectPath = demoProjectPath,
                tokensIn = 4100,
                tokensOut = 1890,
                costUsd = 0.0129
            )

            createMessage(
                taskId = errorTaskId,
                role = MessageRole.USER,
                content = "I have a NullPointerException in PaymentService. Here's the stacktrace:\n\n```\nException in thread \"main\" java.lang.NullPointerException: Cannot invoke \"String.length()\" because \"email\" is null\n    at com.example.service.PaymentService.processPayment(PaymentService.kt:45)\n```",
                createdAt = System.currentTimeMillis() - 1_200_000
            )

            createMessage(
                taskId = errorTaskId,
                role = MessageRole.ASSISTANT,
                content = """Let me help debug this. Show me the PaymentService.kt code, specifically line 45.""",
                createdAt = System.currentTimeMillis() - 1_190_000
            )

            createMessage(
                taskId = errorTaskId,
                role = MessageRole.USER,
                content = "@file src/main/kotlin/com/example/service/PaymentService.kt",
                createdAt = System.currentTimeMillis() - 1_180_000
            )

            createMessage(
                taskId = errorTaskId,
                role = MessageRole.ASSISTANT,
                content = """Thanks! I see the problem. Line 45 uses `email.length()` without checking if email is null.

**Problem:**
```kotlin
fun processPayment(user: User, amount: Double) {
    val email = user.email  // can be null!
    if (email.length() > 0) {  // ← NPE here
        sendConfirmation(email)
    }
}
```

**Solution:**
```kotlin
fun processPayment(user: User, amount: Double) {
    val email = user.email
    if (!email.isNullOrEmpty()) {  // safe option
        sendConfirmation(email)
    }
}
```

Would you like me to apply this fix?""",
                createdAt = System.currentTimeMillis() - 1_170_000
            )

            createMessage(
                taskId = errorTaskId,
                role = MessageRole.USER,
                content = "Yes, apply it",
                createdAt = System.currentTimeMillis() - 1_160_000
            )

            // Assistant applies fix
            createMessage(
                taskId = errorTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCallData(
                        id = "call_fix_1",
                        name = "code_editing",
                        arguments = """{"path":"src/main/kotlin/com/example/service/PaymentService.kt","old_string":"if (email.length() > 0) {","new_string":"if (!email.isNullOrEmpty()) {"}"""
                    )
                ),
                createdAt = System.currentTimeMillis() - 1_150_000
            )

            createMessage(
                taskId = errorTaskId,
                role = MessageRole.TOOL,
                content = "File updated successfully",
                toolCallId = "call_fix_1",
                createdAt = System.currentTimeMillis() - 1_140_000
            )

            createMessage(
                taskId = errorTaskId,
                role = MessageRole.ASSISTANT,
                content = """✅ **Fix applied**

Changed `email.length() > 0` to `!email.isNullOrEmpty()`, which safely checks for null and empty string.

**Recommendation:** Consider using non-nullable types in Kotlin:
```kotlin
data class User(
    val email: String  // never null
)
```

Then the NPE won't occur at all.""",
                createdAt = System.currentTimeMillis() - 1_130_000
            )

            // ========================================
            // SCENARIO 5: Long conversation with multiple turns
            // ========================================
            val longTaskId = createTask(
                name = "Implement REST API - User Management",
                mode = TaskMode.AGENT,
                status = TaskStatus.RUNNING,
                executionMode = ExecutionMode.AUTO,
                projectId = demoProjectId,
                projectPath = demoProjectPath,
                tokensIn = 8900,
                tokensOut = 4200,
                costUsd = 0.0396,
                pinned = true
            )

            val baseTime = System.currentTimeMillis() - 3_600_000

            createMessage(
                taskId = longTaskId,
                role = MessageRole.USER,
                content = "I want to create a REST API for user management. Need CRUD endpoints.",
                createdAt = baseTime
            )

            createMessage(
                taskId = longTaskId,
                role = MessageRole.ASSISTANT,
                content = """Great! Let's create a complete REST API for users. Plan:

1. **GET /api/users** - list all users
2. **GET /api/users/{id}** - get user details
3. **POST /api/users** - create new user
4. **PUT /api/users/{id}** - update user
5. **DELETE /api/users/{id}** - delete user

Starting by analyzing existing project structure...""",
                createdAt = baseTime + 10_000
            )

            // Turn 1: File search
            createMessage(
                taskId = longTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCallData(
                        id = "call_long_1",
                        name = "read_directory",
                        arguments = """{"path":"src/main/kotlin","recursive":true,"max_depth":3}"""
                    )
                ),
                createdAt = baseTime + 20_000
            )

            createMessage(
                taskId = longTaskId,
                role = MessageRole.TOOL,
                content = """Directory structure:
src/main/kotlin/
├── com/example/
│   ├── Application.kt
│   └── model/
│       └── User.kt""",
                toolCallId = "call_long_1",
                createdAt = baseTime + 30_000
            )

            createMessage(
                taskId = longTaskId,
                role = MessageRole.ASSISTANT,
                content = """I see you already have a User model. Proceeding to create controller and repository.""",
                createdAt = baseTime + 40_000
            )

            // Turn 2: Create repository
            createMessage(
                taskId = longTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCallData(
                        id = "call_long_2",
                        name = "create_new_file",
                        arguments = """{"path":"src/main/kotlin/com/example/repository/UserRepository.kt","content":"package com.example.repository\n\nimport com.example.model.User\nimport java.util.concurrent.ConcurrentHashMap\n\nobject UserRepository {\n    private val users = ConcurrentHashMap<String, User>()\n    \n    fun findAll(): List<User> = users.values.toList()\n    \n    fun findById(id: String): User? = users[id]\n    \n    fun save(user: User): User {\n        users[user.id] = user\n        return user\n    }\n    \n    fun delete(id: String): Boolean = users.remove(id) != null\n}"}"""
                    )
                ),
                createdAt = baseTime + 50_000
            )

            createMessage(
                taskId = longTaskId,
                role = MessageRole.TOOL,
                content = "File created: src/main/kotlin/com/example/repository/UserRepository.kt",
                toolCallId = "call_long_2",
                createdAt = baseTime + 60_000
            )

            createMessage(
                taskId = longTaskId,
                role = MessageRole.ASSISTANT,
                content = """Created repository with ConcurrentHashMap for in-memory user storage.

Now creating the REST controller...""",
                createdAt = baseTime + 70_000
            )

            // Turn 3: Create controller (in progress)
            createMessage(
                taskId = longTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCallData(
                        id = "call_long_3",
                        name = "create_new_file",
                        arguments = """{"path":"src/main/kotlin/com/example/controller/UserController.kt","content":"package com.example.controller\n\nimport com.example.model.User\nimport com.example.repository.UserRepository\nimport io.ktor.server.application.*\nimport io.ktor.server.request.*\nimport io.ktor.server.response.*\nimport io.ktor.server.routing.*\n\nfun Route.userRoutes() {\n    route(\"/api/users\") {\n        get {\n            call.respond(UserRepository.findAll())\n        }\n        \n        get(\"/{id}\") {\n            val id = call.parameters[\"id\"]!!\n            val user = UserRepository.findById(id)\n            if (user != null) {\n                call.respond(user)\n            } else {\n                call.respondText(\"User not found\", status = io.ktor.http.HttpStatusCode.NotFound)\n            }\n        }\n        \n        post {\n            val user = call.receive<User>()\n            val created = UserRepository.save(user)\n            call.respond(created)\n        }\n    }\n}"}"""
                    )
                ),
                createdAt = baseTime + 80_000
            )

            createMessage(
                taskId = longTaskId,
                role = MessageRole.TOOL,
                content = "File created: src/main/kotlin/com/example/controller/UserController.kt",
                toolCallId = "call_long_3",
                createdAt = baseTime + 90_000
            )

            // ========================================
            // SCENARIO 6: Failed task with error
            // ========================================
            val failedTaskId = createTask(
                name = "External API Integration",
                mode = TaskMode.AGENT,
                status = TaskStatus.FAILED,
                executionMode = ExecutionMode.INTERACTIVE,
                projectId = demoProjectId,
                projectPath = demoProjectPath,
                rate = -1, // negative rating
                tokensIn = 2100,
                tokensOut = 890,
                costUsd = 0.0060
            )

            createMessage(
                taskId = failedTaskId,
                role = MessageRole.USER,
                content = "Connect to API https://api.example.com/v1/users and fetch user list",
                createdAt = System.currentTimeMillis() - 2_400_000
            )

            createMessage(
                taskId = failedTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCallData(
                        id = "call_failed_1",
                        name = "run_terminal_command",
                        arguments = """{"command":"curl https://api.example.com/v1/users"}"""
                    )
                ),
                createdAt = System.currentTimeMillis() - 2_390_000
            )

            createMessage(
                taskId = failedTaskId,
                role = MessageRole.TOOL,
                content = """Error: Command 'run_terminal_command' is disabled due to security restrictions.

Please use alternative methods for HTTP requests or enable the tool in settings.""",
                toolCallId = "call_failed_1",
                createdAt = System.currentTimeMillis() - 2_380_000
            )

            createMessage(
                taskId = failedTaskId,
                role = MessageRole.ASSISTANT,
                content = """❌ **Cannot complete this task**

The `run_terminal_command` tool is disabled for security reasons.

**Alternatives:**
1. Use @url context provider to fetch data
2. Enable terminal in settings (not recommended)
3. Write HTTP client code in Kotlin

Would you like me to try an alternative method?""",
                createdAt = System.currentTimeMillis() - 2_370_000
            )

            // ========================================
            // SCENARIO 7: Canceled task
            // ========================================
            val canceledTaskId = createTask(
                name = "Generate documentation",
                mode = TaskMode.PLAN,
                status = TaskStatus.CANCELED,
                executionMode = ExecutionMode.INTERACTIVE,
                projectId = demoProjectId,
                projectPath = demoProjectPath,
                tokensIn = 1200,
                tokensOut = 450,
                costUsd = 0.0027
            )

            createMessage(
                taskId = canceledTaskId,
                role = MessageRole.USER,
                content = "Generate documentation for all classes in the project",
                createdAt = System.currentTimeMillis() - 1_800_000
            )

            createMessage(
                taskId = canceledTaskId,
                role = MessageRole.ASSISTANT,
                content = "Starting by scanning project structure...",
                createdAt = System.currentTimeMillis() - 1_790_000
            )

            createMessage(
                taskId = canceledTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCallData(
                        id = "call_canceled_1",
                        name = "grep_search",
                        arguments = """{"pattern":"class \\w+","path":"src","file_pattern":"*.kt"}"""
                    )
                ),
                createdAt = System.currentTimeMillis() - 1_780_000
            )

            // System message about cancellation
            createMessage(
                taskId = canceledTaskId,
                role = MessageRole.SYSTEM,
                content = "⚠️ Task canceled by user",
                createdAt = System.currentTimeMillis() - 1_770_000
            )

            // ========================================
            // SCENARIO 8: Tool result with summarization
            // ========================================
            val summarizeTaskId = createTask(
                name = "Application log analysis",
                mode = TaskMode.AGENT,
                status = TaskStatus.SUCCESS,
                projectId = demoProjectId,
                projectPath = demoProjectPath,
                tokensIn = 5600,
                tokensOut = 2300,
                costUsd = 0.0192
            )

            createMessage(
                taskId = summarizeTaskId,
                role = MessageRole.USER,
                content = "Analyze application.log and find all errors",
                createdAt = System.currentTimeMillis() - 4_200_000
            )

            createMessage(
                taskId = summarizeTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCallData(
                        id = "call_sum_1",
                        name = "read_file",
                        arguments = """{"path":"logs/application.log"}"""
                    )
                ),
                createdAt = System.currentTimeMillis() - 4_190_000
            )

            createMessage(
                taskId = summarizeTaskId,
                role = MessageRole.TOOL,
                content = """[2024-01-15 10:23:45] INFO  Application starting...
[2024-01-15 10:23:46] INFO  Database connected
[2024-01-15 10:23:47] ERROR Failed to connect to payment gateway: Connection timeout
[2024-01-15 10:23:48] WARN  Retry attempt 1/3
[2024-01-15 10:23:50] ERROR Failed to connect to payment gateway: Connection timeout
[2024-01-15 10:23:52] WARN  Retry attempt 2/3
[2024-01-15 10:23:55] ERROR Failed to connect to payment gateway: Connection timeout
[2024-01-15 10:23:58] ERROR Payment service unavailable after 3 retries
[2024-01-15 10:24:01] INFO  Starting background job scheduler
[2024-01-15 10:24:02] ERROR Job failed: NullPointerException at com.example.jobs.PaymentJob.execute
[2024-01-15 10:24:05] INFO  Application ready
[2024-01-15 10:30:12] ERROR Failed to parse user input: Invalid JSON format
[2024-01-15 10:30:15] WARN  Request validation failed for user ID: null
... (8500 more lines)""",
                toolCallId = "call_sum_1",
                isSummarized = true,
                rawOutput = """Full 10000 line log content here...""",
                createdAt = System.currentTimeMillis() - 4_180_000
            )

            createMessage(
                taskId = summarizeTaskId,
                role = MessageRole.ASSISTANT,
                content = """## Log Analysis - Errors Found

Analyzed log file (10,000 lines, summarized for display). Found **3 critical issues**:

### 1. ⛔ Payment Gateway Unreachable
- **Frequency:** 3 times between 10:23:47 - 10:23:58
- **Error:** `Connection timeout`
- **Impact:** Cannot process payments
- **Location:** PaymentService

### 2. ⛔ NullPointerException in Job Scheduler
- **Time:** 10:24:02
- **Location:** `com.example.jobs.PaymentJob.execute`
- **Cause:** Missing null handling for input data

### 3. ⚠️ Validation Errors
- **Example:** `Invalid JSON format`, `user ID: null`
- **Frequency:** Sporadic
- **Impact:** Rejected API requests

**Recommendations:**
1. Configure timeout for payment gateway
2. Add null-checks in PaymentJob
3. Enable stricter validation on API gateway""",
                createdAt = System.currentTimeMillis() - 4_170_000
            )

            // ========================================
            // SCENARIO 9: Multiple tools in single turn
            // ========================================
            val multiToolTaskId = createTask(
                name = "Repository refactoring",
                mode = TaskMode.AGENT,
                status = TaskStatus.SUCCESS,
                executionMode = ExecutionMode.AUTO,
                projectId = demoProjectId,
                projectPath = demoProjectPath,
                tokensIn = 3200,
                tokensOut = 1450,
                costUsd = 0.0093
            )

            createMessage(
                taskId = multiToolTaskId,
                role = MessageRole.USER,
                content = "Refactor UserRepository - add findByEmail method",
                createdAt = System.currentTimeMillis() - 2_700_000
            )

            // Multiple tool calls in one message
            createMessage(
                taskId = multiToolTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCallData(
                        id = "call_multi_1",
                        name = "file_search",
                        arguments = """{"pattern":"UserRepository.kt"}"""
                    ),
                    ToolCallData(
                        id = "call_multi_2",
                        name = "grep_search",
                        arguments = """{"pattern":"interface UserRepository|class UserRepository","path":"src"}"""
                    ),
                    ToolCallData(
                        id = "call_multi_3",
                        name = "read_directory",
                        arguments = """{"path":"src/main/kotlin/com/example/repository","recursive":false}"""
                    )
                ),
                createdAt = System.currentTimeMillis() - 2_690_000
            )

            // Results come back in order
            createMessage(
                taskId = multiToolTaskId,
                role = MessageRole.TOOL,
                content = """Found 1 file:
- src/main/kotlin/com/example/repository/UserRepository.kt""",
                toolCallId = "call_multi_1",
                createdAt = System.currentTimeMillis() - 2_680_000
            )

            createMessage(
                taskId = multiToolTaskId,
                role = MessageRole.TOOL,
                content = """Found 2 matches:
- src/main/kotlin/com/example/repository/UserRepository.kt:1:interface UserRepository
- src/main/kotlin/com/example/repository/UserRepositoryImpl.kt:5:class UserRepositoryImpl : UserRepository""",
                toolCallId = "call_multi_2",
                createdAt = System.currentTimeMillis() - 2_670_000
            )

            createMessage(
                taskId = multiToolTaskId,
                role = MessageRole.TOOL,
                content = """Directory contents:
- UserRepository.kt (interface)
- UserRepositoryImpl.kt (implementation)
- Database.kt""",
                toolCallId = "call_multi_3",
                createdAt = System.currentTimeMillis() - 2_660_000
            )

            createMessage(
                taskId = multiToolTaskId,
                role = MessageRole.ASSISTANT,
                content = """Found the repository. Adding `findByEmail` method to both interface and implementation.""",
                createdAt = System.currentTimeMillis() - 2_650_000
            )

            createMessage(
                taskId = multiToolTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCallData(
                        id = "call_multi_4",
                        name = "code_editing",
                        arguments = """{"path":"src/main/kotlin/com/example/repository/UserRepository.kt","old_string":"interface UserRepository {\n    fun findAll(): List<User>\n    fun findById(id: String): User?","new_string":"interface UserRepository {\n    fun findAll(): List<User>\n    fun findById(id: String): User?\n    fun findByEmail(email: String): User?"}"""
                    )
                ),
                createdAt = System.currentTimeMillis() - 2_640_000
            )

            createMessage(
                taskId = multiToolTaskId,
                role = MessageRole.TOOL,
                content = "Updated interface",
                toolCallId = "call_multi_4",
                createdAt = System.currentTimeMillis() - 2_630_000
            )

            createMessage(
                taskId = multiToolTaskId,
                role = MessageRole.ASSISTANT,
                content = """✅ **Refactoring complete**

Added `findByEmail(email: String): User?` method to:
- UserRepository interface
- UserRepositoryImpl implementation

Method uses database index on email column for performance.""",
                createdAt = System.currentTimeMillis() - 2_620_000
            )

            // ========================================
            // SCENARIO 10: Rated conversation (positive)
            // ========================================
            val ratedTaskId = createTask(
                name = "Builder pattern explanation",
                mode = TaskMode.CHAT,
                status = TaskStatus.SUCCESS,
                projectId = demoProjectId,
                projectPath = demoProjectPath,
                rate = 1, // positive rating
                tokensIn = 1500,
                tokensOut = 2100,
                costUsd = 0.0108
            )

            createMessage(
                taskId = ratedTaskId,
                role = MessageRole.USER,
                content = "What is the Builder pattern and when should I use it?",
                createdAt = System.currentTimeMillis() - 150_000
            )

            createMessage(
                taskId = ratedTaskId,
                role = MessageRole.ASSISTANT,
                content = """# Builder Design Pattern

**Builder** is a creational design pattern that allows constructing complex objects step by step.

## When to use?

✅ **Use when:**
- Object has many optional parameters
- You want to create objects in different configurations
- Object creation code is complex

❌ **Don't use when:**
- Object is simple (3-4 fields)
- All parameters are required

## Example in Kotlin

```kotlin
// Traditional Builder (Java style)
class UserBuilder {
    private var name: String? = null
    private var email: String? = null
    private var age: Int? = null

    fun name(name: String) = apply { this.name = name }
    fun email(email: String) = apply { this.email = email }
    fun age(age: Int) = apply { this.age = age }

    fun build() = User(name!!, email!!, age)
}

// Usage
val user = UserBuilder()
    .name("John")
    .email("john@example.com")
    .age(30)
    .build()
```

```kotlin
// Kotlin DSL (idiomatic)
data class User(
    val name: String,
    val email: String,
    val age: Int? = null
)

// Usage with named arguments
val user = User(
    name = "John",
    email = "john@example.com"
    // age is optional
)
```

**Conclusion:** In Kotlin, you often don't need Builder thanks to named arguments!""",
                createdAt = System.currentTimeMillis() - 140_000
            )

            // ========================================
            // SCENARIO 11: AGENT with advance_code_editing (streaming LLM tool)
            // ========================================
            val advanceEditTaskId = createTask(
                name = "Add error handling with LLM",
                mode = TaskMode.AGENT,
                status = TaskStatus.SUCCESS,
                executionMode = ExecutionMode.AUTO,
                projectId = demoProjectId,
                projectPath = demoProjectPath,
                pinned = true,
                tokensIn = 2100,
                tokensOut = 3450,
                costUsd = 0.0234
            )

            createMessage(
                taskId = advanceEditTaskId,
                role = MessageRole.USER,
                content = "Add comprehensive error handling to PaymentService.kt using advance_code_editing",
                createdAt = System.currentTimeMillis() - 5_000_000
            )

            // Assistant decides to use advance_code_editing
            val toolCallAdv1 = ToolCallData(
                id = "call_adv_1",
                name = "advance_code_editing",
                arguments = """{"path":"src/main/kotlin/com/example/service/PaymentService.kt","edit_description":"Add comprehensive error handling with try-catch blocks, logging, and custom exceptions for all payment operations"}"""
            )
            createMessage(
                taskId = advanceEditTaskId,
                role = MessageRole.ASSISTANT,
                content = "I'll use advance_code_editing to add comprehensive error handling to the payment service.",
                toolCalls = listOf(toolCallAdv1),
                createdAt = System.currentTimeMillis() - 4_990_000
            )

            // Simulated streaming result (would be streamed in real scenario)
            createMessage(
                taskId = advanceEditTaskId,
                role = MessageRole.TOOL,
                content = """File edited successfully: src/main/kotlin/com/example/service/PaymentService.kt
Model: claude-sonnet-4-5, Tokens: 1200/2450, Cost: $0.0187
Diff:
--- a/PaymentService.kt
+++ b/PaymentService.kt
@@ -1,5 +1,15 @@
 package com.example.service

+sealed class PaymentError(message: String) : Exception(message)
+class PaymentTimeoutError : PaymentError("Payment timed out")
+class InvalidPaymentAmountError : PaymentError("Invalid amount")
+
 class PaymentService {
+    private val logger = LoggerFactory.getLogger(PaymentService::class.java)
+
     fun processPayment(user: User, amount: Double) {
-        val gateway = PaymentGateway()
-        gateway.charge(amount)
+        try {
+            if (amount <= 0) {
+                throw InvalidPaymentAmountError()
+            }
+            val gateway = PaymentGateway()
+            gateway.charge(amount, timeoutMs = 30000)
+            logger.info("Payment processed successfully")
+        } catch (e: TimeoutException) {
+            logger.error("Payment timeout", e)
+            throw PaymentTimeoutError()
+        } catch (e: Exception) {
+            logger.error("Payment failed", e)
+            throw PaymentError("Payment failed: $\{e.message\}")
+        }
     }
 }""",
                toolCallId = "call_adv_1",
                createdAt = System.currentTimeMillis() - 4_980_000
            )

            createMessage(
                taskId = advanceEditTaskId,
                role = MessageRole.ASSISTANT,
                content = """✅ **Error Handling Added**

Enhanced PaymentService with comprehensive error handling:

**Added:**
- Custom exception hierarchy (`PaymentError`, `PaymentTimeoutError`, `InvalidPaymentAmountError`)
- Try-catch blocks with specific error handling
- Structured logging for all operations
- Input validation (amount > 0)
- Timeout configuration (30 seconds)

The code now properly handles:
- Invalid payment amounts
- Network timeouts
- Gateway failures
- Unexpected errors""",
                createdAt = System.currentTimeMillis() - 4_970_000
            )

            // ========================================
            // SCENARIO 12: AGENT with multi_line_editor (streaming LLM tool)
            // ========================================
            val multiLineEditTaskId = createTask(
                name = "Refactor with multi_line_editor",
                mode = TaskMode.AGENT,
                status = TaskStatus.SUCCESS,
                executionMode = ExecutionMode.AUTO,
                projectId = demoProjectId,
                projectPath = demoProjectPath,
                tokensIn = 1650,
                tokensOut = 890,
                costUsd = 0.0068
            )

            createMessage(
                taskId = multiLineEditTaskId,
                role = MessageRole.USER,
                content = "Add null checks to all functions in UserService.kt using multi_line_editor",
                createdAt = System.currentTimeMillis() - 5_500_000
            )

            val toolCallMle1 = ToolCallData(
                id = "call_mle_1",
                name = "multi_line_editor",
                arguments = """{"path":"src/main/kotlin/com/example/service/UserService.kt","edit_description":"Add null safety checks to getUserById, updateUser, and deleteUser functions. Validate that id is not blank and user is not null where appropriate."}"""
            )
            createMessage(
                taskId = multiLineEditTaskId,
                role = MessageRole.ASSISTANT,
                content = "I'll use multi_line_editor to add null checks efficiently.",
                toolCalls = listOf(toolCallMle1),
                createdAt = System.currentTimeMillis() - 5_490_000
            )

            createMessage(
                taskId = multiLineEditTaskId,
                role = MessageRole.TOOL,
                content = """File edited successfully: src/main/kotlin/com/example/service/UserService.kt
Applied 3 edits to file
Model: claude-sonnet-4-5, Tokens: 800/450, Cost: $0.0032
Diff:
--- a/UserService.kt
+++ b/UserService.kt
@@ -5,8 +5,12 @@
     fun getUserById(id: String): User? {
-        return users[id]
+        require(id.isNotBlank()) { "User ID cannot be blank" }
+        return users[id] ?: throw NoSuchElementException("User not found: $id")
     }

     fun updateUser(user: User?) {
+        requireNotNull(user) { "User cannot be null" }
         users[user.id] = user
     }

     fun deleteUser(id: String) {
+        require(id.isNotBlank()) { "User ID cannot be blank" }
         users.remove(id)
     }
 }""",
                toolCallId = "call_mle_1",
                createdAt = System.currentTimeMillis() - 5_480_000
            )

            createMessage(
                taskId = multiLineEditTaskId,
                role = MessageRole.ASSISTANT,
                content = """✅ **Null Safety Added**

Applied 3 targeted edits to UserService:

1. **getUserById**: Added blank check and explicit null handling
2. **updateUser**: Added null requirement with descriptive message
3. **deleteUser**: Added blank check for ID

All functions now have proper null safety with clear error messages.""",
                createdAt = System.currentTimeMillis() - 5_470_000
            )

            // ========================================
            // SCENARIO 13: Complex multi-tool workflow (read_directory + grep + multiple edits)
            // ========================================
            val complexWorkflowTaskId = createTask(
                name = "Add logging to entire service layer",
                mode = TaskMode.AGENT,
                status = TaskStatus.RUNNING,
                executionMode = ExecutionMode.AUTO,
                projectId = demoProjectId,
                projectPath = demoProjectPath,
                tokensIn = 5200,
                tokensOut = 3100,
                costUsd = 0.0256
            )

            val baseTimeComplex = System.currentTimeMillis() - 7_000_000

            createMessage(
                taskId = complexWorkflowTaskId,
                role = MessageRole.USER,
                content = "Add structured logging to all service classes in the project",
                createdAt = baseTimeComplex
            )

            // Turn 1: Explore structure
            val toolCallComplex1 = ToolCallData(
                id = "call_complex_1",
                name = "read_directory",
                arguments = """{"path":"src/main/kotlin/com/example/service","recursive":false}"""
            )
            createMessage(
                taskId = complexWorkflowTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(toolCallComplex1),
                createdAt = baseTimeComplex + 10_000
            )

            createMessage(
                taskId = complexWorkflowTaskId,
                role = MessageRole.TOOL,
                content = """Directory contents:
- UserService.kt
- PaymentService.kt
- OrderService.kt
- NotificationService.kt
- AuthService.kt""",
                toolCallId = "call_complex_1",
                createdAt = baseTimeComplex + 20_000
            )

            // Turn 2: Check which services have logging
            val toolCallComplex2 = ToolCallData(
                id = "call_complex_2",
                name = "grep_search",
                arguments = """{"pattern":"private val logger|LoggerFactory.getLogger","path":"src/main/kotlin/com/example/service","file_pattern":"*.kt"}"""
            )
            createMessage(
                taskId = complexWorkflowTaskId,
                role = MessageRole.ASSISTANT,
                content = "Checking which services already have logging...",
                toolCalls = listOf(toolCallComplex2),
                createdAt = baseTimeComplex + 30_000
            )

            createMessage(
                taskId = complexWorkflowTaskId,
                role = MessageRole.TOOL,
                content = """Found 2 matches:
- PaymentService.kt:15:private val logger = LoggerFactory.getLogger(PaymentService::class.java)
- AuthService.kt:8:private val logger = LoggerFactory.getLogger(AuthService::class.java)""",
                toolCallId = "call_complex_2",
                createdAt = baseTimeComplex + 40_000
            )

            createMessage(
                taskId = complexWorkflowTaskId,
                role = MessageRole.ASSISTANT,
                content = """Found 5 service classes:
- ✅ PaymentService.kt (has logging)
- ✅ AuthService.kt (has logging)
- ❌ UserService.kt (needs logging)
- ❌ OrderService.kt (needs logging)
- ❌ NotificationService.kt (needs logging)

Proceeding to add logging to UserService, OrderService, and NotificationService.""",
                createdAt = baseTimeComplex + 50_000
            )

            // Turn 3: Add logging to UserService using multi_line_editor
            val toolCallComplex3 = ToolCallData(
                id = "call_complex_3",
                name = "multi_line_editor",
                arguments = """{"path":"src/main/kotlin/com/example/service/UserService.kt","edit_description":"Add private val logger at class level and add logging to all public methods: log entry with parameters, log exit with result, log errors with stacktrace"}"""
            )
            createMessage(
                taskId = complexWorkflowTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(toolCallComplex3),
                createdAt = baseTimeComplex + 60_000
            )

            createMessage(
                taskId = complexWorkflowTaskId,
                role = MessageRole.TOOL,
                content = "File edited successfully: src/main/kotlin/com/example/service/UserService.kt\nApplied 4 edits",
                toolCallId = "call_complex_3",
                createdAt = baseTimeComplex + 70_000
            )

            // Turn 4: Add logging to OrderService using multi_line_editor
            val toolCallComplex4 = ToolCallData(
                id = "call_complex_4",
                name = "multi_line_editor",
                arguments = """{"path":"src/main/kotlin/com/example/service/OrderService.kt","edit_description":"Add private val logger and logging to all public methods similar to UserService"}"""
            )
            createMessage(
                taskId = complexWorkflowTaskId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(toolCallComplex4),
                createdAt = baseTimeComplex + 80_000
            )

            createMessage(
                taskId = complexWorkflowTaskId,
                role = MessageRole.TOOL,
                content = "File edited successfully: src/main/kotlin/com/example/service/OrderService.kt\nApplied 3 edits",
                toolCallId = "call_complex_4",
                createdAt = baseTimeComplex + 90_000
            )

            // ========================================
            // SCENARIO 14: AGENT mode with file_search + read_directory + grep (analysis workflow)
            // ========================================
            val analysisTaskId = createTask(
                name = "Analyze database query patterns",
                mode = TaskMode.AGENT,
                status = TaskStatus.SUCCESS,
                executionMode = ExecutionMode.AUTO,
                projectId = demoProjectId,
                projectPath = demoProjectPath,
                tokensIn = 2800,
                tokensOut = 1200,
                costUsd = 0.0096
            )

            createMessage(
                taskId = analysisTaskId,
                role = MessageRole.USER,
                content = "Analyze all database queries in the project and identify potential N+1 query problems",
                createdAt = System.currentTimeMillis() - 8_500_000
            )

            val toolCallAn1 = ToolCallData(
                id = "call_an_1",
                name = "file_search",
                arguments = """{"pattern":"*Repository.kt","path":"src"}"""
            )
            val toolCallAn2 = ToolCallData(
                id = "call_an_2",
                name = "grep_search",
                arguments = """{"pattern":"\\.select\\{\\s*\\.forEach","path":"src"}"""
            )
            val toolCallAn3 = ToolCallData(
                id = "call_an_3",
                name = "read_directory",
                arguments = """{"path":"src/main/kotlin/com/example/repository","recursive":false}"""
            )

            createMessage(
                taskId = analysisTaskId,
                role = MessageRole.ASSISTANT,
                content = "I'll search for repositories and analyze query patterns for N+1 problems.",
                toolCalls = listOf(toolCallAn1, toolCallAn2, toolCallAn3),
                createdAt = System.currentTimeMillis() - 8_490_000
            )

            createMessage(
                taskId = analysisTaskId,
                role = MessageRole.TOOL,
                content = "Found 8 repository files",
                toolCallId = "call_an_1",
                createdAt = System.currentTimeMillis() - 8_480_000
            )

            createMessage(
                taskId = analysisTaskId,
                role = MessageRole.TOOL,
                content = """Found 3 potential N+1 query problems:
- OrderRepository.kt:45: select { forEach { users.select } }
- OrderRepository.kt:78: select { forEach { items.select } }
- UserService.kt:123: select { forEach { orders.select } }""",
                toolCallId = "call_an_2",
                createdAt = System.currentTimeMillis() - 8_470_000
            )

            createMessage(
                taskId = analysisTaskId,
                role = MessageRole.TOOL,
                content = """Directory contents:
- OrderRepository.kt
- UserRepository.kt
- ProductRepository.kt
- etc.""",
                toolCallId = "call_an_3",
                createdAt = System.currentTimeMillis() - 8_460_000
            )

            createMessage(
                taskId = analysisTaskId,
                role = MessageRole.ASSISTANT,
                content = """## Database Query Analysis Results

**Found 3 N+1 Query Problems:**

### 1. OrderRepository.kt:45
```kotlin
orders.forEach { order ->
    val user = users.select { ... }.single()
    // N+1: User query executed for each order
}
```

### 2. OrderRepository.kt:78
```kotlin
orders.forEach { order ->
    val items = orderItems.select { ... }.toList()
    // N+1: Items query executed for each order
}
```

### 3. UserService.kt:123
```kotlin
users.forEach { user ->
    val orders = userOrders.select { ... }.toList()
    // N+1: Orders query executed for each user
}
```

**Recommendations:**
1. Use Exposed's innerJoin for related entities
2. Batch load with `inList` where possible
3. Consider pagination for large result sets

Would you like me to refactor these queries?""",
                createdAt = System.currentTimeMillis() - 8_450_000
            )

            // ========================================
            // SCENARIO 15: Multiple file operations in sequence (create + edit + read)
            // ========================================
            val sequenceTaskId = createTask(
                name = "Create configuration system",
                mode = TaskMode.AGENT,
                status = TaskStatus.SUCCESS,
                executionMode = ExecutionMode.AUTO,
                projectId = demoProjectId,
                projectPath = demoProjectPath,
                tokensIn = 3200,
                tokensOut = 2100,
                costUsd = 0.0152
            )

            createMessage(
                taskId = sequenceTaskId,
                role = MessageRole.USER,
                content = "Create a configuration management system with ConfigManager class and application.yaml",
                createdAt = System.currentTimeMillis() - 10_000_000
            )

            // Turn 1: Create ConfigManager
            val toolCallSeq1 = ToolCallData(
                id = "call_seq_1",
                name = "create_new_file",
                arguments = """{"path":"src/main/kotlin/com/example/config/ConfigManager.kt","content":"package com.example.config\n\nobject ConfigManager {\n    private val config = loadConfig()\n    \n    fun get(key: String): String? = config[key]\n    fun getInt(key: String): Int = get(key)?.toInt() ?: 0\n    \n    private fun loadConfig(): Map<String, String> {\n        // Load from resources/application.yaml\n        return mapOf(\n            \"app.name\" to \"MyApp\",\n            \"app.version\" to \"1.0.0\"\n        )\n    }\n}"}"""
            )
            createMessage(
                taskId = sequenceTaskId,
                role = MessageRole.ASSISTANT,
                content = "Creating ConfigManager class...",
                toolCalls = listOf(toolCallSeq1),
                createdAt = System.currentTimeMillis() - 9_990_000
            )

            createMessage(
                taskId = sequenceTaskId,
                role = MessageRole.TOOL,
                content = "File created: src/main/kotlin/com/example/config/ConfigManager.kt",
                toolCallId = "call_seq_1",
                createdAt = System.currentTimeMillis() - 9_980_000
            )

            // Turn 2: Create application.yaml
            val toolCallSeq2 = ToolCallData(
                id = "call_seq_2",
                name = "create_new_file",
                arguments = """{"path":"src/main/resources/application.yaml","content":"app:\n  name: MyApp\n  version: 1.0.0\n  mode: production\n\ndatabase:\n  url: jdbc:postgresql://localhost:5432/mydb\n  poolSize: 10\n\nlogging:\n  level: INFO\n  file: logs/application.log"}"""
            )
            createMessage(
                taskId = sequenceTaskId,
                role = MessageRole.ASSISTANT,
                content = "Creating application.yaml configuration file...",
                toolCalls = listOf(toolCallSeq2),
                createdAt = System.currentTimeMillis() - 9_970_000
            )

            createMessage(
                taskId = sequenceTaskId,
                role = MessageRole.TOOL,
                content = "File created: src/main/resources/application.yaml",
                toolCallId = "call_seq_2",
                createdAt = System.currentTimeMillis() - 9_960_000
            )

            // Turn 3: Read to verify
            val toolCallSeq3 = ToolCallData(
                id = "call_seq_3",
                name = "read_file",
                arguments = """{"path":"src/main/kotlin/com/example/config/ConfigManager.kt"}"""
            )
            createMessage(
                taskId = sequenceTaskId,
                role = MessageRole.ASSISTANT,
                content = "Verifying ConfigManager was created correctly...",
                toolCalls = listOf(toolCallSeq3),
                createdAt = System.currentTimeMillis() - 9_950_000
            )

            createMessage(
                taskId = sequenceTaskId,
                role = MessageRole.TOOL,
                content = """```kotlin
package com.example.config

object ConfigManager {
    private val config = loadConfig()

    fun get(key: String): String? = config[key]
    fun getInt(key: String): Int = get(key)?.toInt() ?: 0

    private fun loadConfig(): Map<String, String> {
        return mapOf(
            "app.name" to "MyApp",
            "app.version" to "1.0.0"
        )
    }
}
```""",
                toolCallId = "call_seq_3",
                createdAt = System.currentTimeMillis() - 9_940_000
            )

            createMessage(
                taskId = sequenceTaskId,
                role = MessageRole.ASSISTANT,
                content = """✅ **Configuration System Created**

Created complete configuration management system:

1. **ConfigManager.kt**
   - Singleton object for global access
   - Type-safe getters: `get()`, `getInt()`
   - Loads from YAML resources

2. **application.yaml**
   - Application settings (name, version, mode)
   - Database configuration
   - Logging configuration

**Usage:**
```kotlin
val appName = ConfigManager.get("app.name")
val poolSize = ConfigManager.getInt("database.poolSize")
```

The system is ready for use. Next steps: add YAML parsing library and environment-specific configs.""",
                createdAt = System.currentTimeMillis() - 9_930_000
            )

            logger.info { "Test data seeding completed!" }
            logger.info { "Created 15 tasks with various conversation scenarios" }
        }
    }

    // Helper functions
    private fun createTask(
        name: String,
        mode: TaskMode,
        status: TaskStatus,
        projectId: String,
        projectPath: String,
        readOnly: Boolean = false,
        pinned: Boolean = false,
        executionMode: ExecutionMode = ExecutionMode.INTERACTIVE,
        requiresPlanApproval: Boolean = false,
        planApproved: Boolean = false,
        sourcePlanId: String? = null,
        planVersion: Int? = null,
        rate: Int? = null,
        tokensIn: Int = 0,
        tokensOut: Int = 0,
        costUsd: Double = 0.0
    ): String {
        val id = java.util.UUID.randomUUID().toString()
        TasksTable.insert {
            it[TasksTable.id] = id
            it[TasksTable.name] = name
            it[TasksTable.mode] = mode
            it[TasksTable.status] = status
            it[TasksTable.readOnly] = readOnly
            it[TasksTable.pinned] = pinned
            it[TasksTable.executionMode] = executionMode
            it[TasksTable.requiresPlanApproval] = requiresPlanApproval
            it[TasksTable.planApproved] = planApproved
            it[TasksTable.projectId] = projectId
            it[TasksTable.projectPath] = projectPath
            it[TasksTable.sourcePlanId] = sourcePlanId
            it[TasksTable.planVersion] = planVersion
            it[TasksTable.rate] = rate
            it[TasksTable.tokensIn] = tokensIn
            it[TasksTable.tokensOut] = tokensOut
            it[TasksTable.costUsd] = costUsd
            it[TasksTable.createdAt] = System.currentTimeMillis()
            it[TasksTable.updatedAt] = System.currentTimeMillis()
        }
        return id
    }

    private fun createMessage(
        taskId: String,
        role: MessageRole,
        content: String,
        toolCalls: List<ToolCallData>? = null,
        toolCallId: String? = null,
        isSummarized: Boolean = false,
        rawOutput: String? = null,
        tokensIn: Int? = null,
        tokensOut: Int? = null,
        cost: Double? = null,
        createdAt: Long = System.currentTimeMillis()
    ) {
        val metadata = if (tokensIn != null || tokensOut != null || cost != null) {
            buildMetadataJson(tokensIn, tokensOut, cost)
        } else null

        ChatMessagesTable.insert {
            it[ChatMessagesTable.taskId] = taskId
            it[ChatMessagesTable.role] = role
            it[ChatMessagesTable.content] = content
            it[ChatMessagesTable.metadata] = metadata
            it[ChatMessagesTable.toolCallsJson] = ToolCallData.toJsonList(toolCalls)
            it[ChatMessagesTable.toolCallId] = toolCallId
            it[ChatMessagesTable.isSummarized] = isSummarized
            it[ChatMessagesTable.rawOutput] = rawOutput
            it[ChatMessagesTable.tokensIn] = tokensIn
            it[ChatMessagesTable.tokensOut] = tokensOut
            it[ChatMessagesTable.cost] = cost
            it[ChatMessagesTable.createdAt] = createdAt
        }
    }

    private fun createSubtask(
        taskId: String,
        orderIndex: Int,
        kind: SubtaskKind,
        status: TaskStatus,
        description: String,
        paramsJson: String? = null,
        stepPlanJson: String? = null,
        summary: String? = null,
        requiresApproval: Boolean = false,
        approvalStatus: ApprovalStatus = ApprovalStatus.NOT_REQUIRED,
        llmModel: String? = null,
        llmProvider: String? = null,
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        costUsd: Double = 0.0
    ) {
        val now = System.currentTimeMillis()
        SubtasksTable.insert {
            it[SubtasksTable.taskId] = taskId
            it[SubtasksTable.orderIndex] = orderIndex
            it[SubtasksTable.kind] = kind
            it[SubtasksTable.status] = status
            it[SubtasksTable.description] = description
            it[SubtasksTable.paramsJson] = paramsJson
            it[SubtasksTable.stepPlanJson] = stepPlanJson
            it[SubtasksTable.summary] = summary
            it[SubtasksTable.requiresApproval] = requiresApproval
            it[SubtasksTable.approvalStatus] = approvalStatus
            it[SubtasksTable.llmModel] = llmModel
            it[SubtasksTable.llmProvider] = llmProvider
            it[SubtasksTable.inputTokens] = inputTokens
            it[SubtasksTable.outputTokens] = outputTokens
            it[SubtasksTable.costUsd] = costUsd
            it[SubtasksTable.createdAt] = now
            it[SubtasksTable.updatedAt] = now
        }
    }

    private fun buildMetadataJson(
        tokensIn: Int?,
        tokensOut: Int?,
        cost: Double?
    ): String {
        val parts = mutableListOf<String>()
        if (tokensIn != null) parts.add("\"tokens_in\":$tokensIn")
        if (tokensOut != null) parts.add("\"tokens_out\":$tokensOut")
        if (cost != null) parts.add("\"cost_usd\":$cost")
        return "{$parts}"
    }
}
