package pl.jclab.refio.api.models

/**
 * Slash command definition.
 * Stored in PromptsTable with kind = 'command'.
 */
data class SlashCommand(
    val id: String,
    val name: String,              // Command name (without /)
    val description: String,       // Shown in autocomplete
    val template: String,          // Prompt template
    val variables: List<String> = emptyList(),  // Template variables
    val category: String = "general",
    val isBuiltin: Boolean = false
) {
    companion object {
        /**
         * Built-in commands
         */
        val BUILTINS = listOf(
            // Code understanding
            SlashCommand(
                id = "explain",
                name = "explain",
                description = "Explain selected code",
                template = """Analyze and explain the following code in detail:
                    |1. What does this code do?
                    |2. How does it work (step-by-step)?
                    |3. What are the key patterns or techniques used?
                    |4. Are there any potential issues or edge cases?
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "understanding",
                isBuiltin = true
            ),

            // Code improvement
            SlashCommand(
                id = "refactor",
                name = "refactor",
                description = "Suggest refactoring",
                template = """Review the following code and suggest refactoring improvements:
                    |1. Apply KISS (Keep It Simple) principle
                    |2. Apply SRP (Single Responsibility Principle)
                    |3. Identify code duplication
                    |4. Suggest better naming or structure
                    |5. Maintain existing functionality
                    |
                    |Provide specific code changes with explanations.
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "improvement",
                isBuiltin = true
            ),
            SlashCommand(
                id = "simplify",
                name = "simplify",
                description = "Simplify code complexity",
                template = """Simplify the following code following KISS principle:
                    |1. Remove unnecessary complexity
                    |2. Reduce nesting levels
                    |3. Make logic more readable
                    |4. Keep the same functionality
                    |
                    |Provide the simplified version with explanation of changes.
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "improvement",
                isBuiltin = true
            ),
            SlashCommand(
                id = "optimize",
                name = "optimize",
                description = "Optimize performance",
                template = """Analyze and optimize the following code for better performance:
                    |1. Identify performance bottlenecks
                    |2. Suggest algorithmic improvements
                    |3. Optimize memory usage
                    |4. Consider time complexity
                    |
                    |Provide optimized code with performance impact explanation.
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "improvement",
                isBuiltin = true
            ),

            // Testing
            SlashCommand(
                id = "test",
                name = "test",
                description = "Generate tests",
                template = """Generate comprehensive unit tests for the following code:
                    |1. Test happy path scenarios
                    |2. Test edge cases and error conditions
                    |3. Use appropriate mocking (MockK for Kotlin)
                    |4. Follow existing test patterns in the project
                    |5. Aim for high code coverage
                    |
                    |Use JUnit 5 and MockK framework.
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "testing",
                isBuiltin = true
            ),

            // Bug fixing
            SlashCommand(
                id = "fix",
                name = "fix",
                description = "Fix errors/warnings",
                template = """Fix the following errors, warnings, or bugs:
                    |1. Identify the root cause
                    |2. Provide a minimal fix (avoid over-engineering)
                    |3. Explain what was wrong
                    |4. Ensure the fix doesn't break existing functionality
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "fixing",
                isBuiltin = true
            ),

            // Documentation
            SlashCommand(
                id = "document",
                name = "document",
                description = "Add documentation",
                template = """Add comprehensive documentation for the following code:
                    |1. Add KDoc/JavaDoc comments for classes and functions
                    |2. Document parameters, return values, and exceptions
                    |3. Explain complex logic with inline comments
                    |4. Focus on 'why' not 'what' in comments
                    |5. Use English language for all documentation
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "documentation",
                isBuiltin = true
            ),

            // Translation commands
            SlashCommand(
                id = "translate-comments",
                name = "translate-comments",
                description = "Translate comments to English",
                template = """Translate all comments in the following code to English:
                    |1. Translate code comments (single-line and multi-line)
                    |2. Translate KDoc/JavaDoc documentation
                    |3. Preserve code functionality - only change comments
                    |4. Use professional technical English
                    |5. Keep the original meaning and context
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "translation",
                isBuiltin = true
            ),
            SlashCommand(
                id = "translate-messages",
                name = "translate-messages",
                description = "Translate string messages to English",
                template = """Translate all user-facing messages and strings to English:
                    |1. Translate string literals (error messages, logs, UI text)
                    |2. Keep technical terms in English
                    |3. Preserve string formatting and placeholders
                    |4. Don't change variable names or code logic
                    |5. Use clear, professional English
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "translation",
                isBuiltin = true
            ),
            SlashCommand(
                id = "translate-all",
                name = "translate-all",
                description = "Translate everything to English",
                template = """Translate all text in the following code to English:
                    |1. Translate all comments and documentation
                    |2. Translate all string literals and messages
                    |3. Consider translating variable/function names if they're in non-English
                    |4. Preserve code functionality
                    |5. Use consistent, professional technical English
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "translation",
                isBuiltin = true
            ),

            // Code enhancement
            SlashCommand(
                id = "add-logging",
                name = "add-logging",
                description = "Add logging statements",
                template = """Add appropriate logging to the following code:
                    |1. Use dualLogger() for plugin code (outputs to IDE log and UI)
                    |2. Add INFO level for important operations
                    |3. Add DEBUG level for detailed execution flow
                    |4. Add ERROR level for exceptions and failures
                    |5. Include meaningful context in log messages
                    |6. Avoid excessive logging that creates noise
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "enhancement",
                isBuiltin = true
            ),
            SlashCommand(
                id = "add-error-handling",
                name = "add-error-handling",
                description = "Add error handling",
                template = """Add proper error handling to the following code:
                    |1. Identify potential failure points
                    |2. Add try-catch blocks where appropriate
                    |3. Throw meaningful exceptions with clear messages
                    |4. Never silently swallow errors
                    |5. Add logging for errors
                    |6. Follow the project's error handling policy (fail explicitly, no silent fallbacks)
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "enhancement",
                isBuiltin = true
            ),
            SlashCommand(
                id = "add-validation",
                name = "add-validation",
                description = "Add input validation",
                template = """Add input validation to the following code:
                    |1. Validate at system boundaries (user input, external APIs)
                    |2. Check for null, empty, or invalid values
                    |3. Throw IllegalArgumentException with descriptive messages
                    |4. Don't add unnecessary validation for internal code
                    |5. Consider edge cases and boundary conditions
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "enhancement",
                isBuiltin = true
            ),

            // Code extraction
            SlashCommand(
                id = "extract-method",
                name = "extract-method",
                description = "Extract method/function",
                template = """Extract the selected code into a separate method/function:
                    |1. Create a well-named function that describes what it does
                    |2. Identify and pass necessary parameters
                    |3. Determine appropriate return type
                    |4. Add KDoc documentation for the new function
                    |5. Replace original code with function call
                    |6. Ensure the extraction improves code readability
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "refactoring",
                isBuiltin = true
            ),

            // Security
            SlashCommand(
                id = "security-review",
                name = "security-review",
                description = "Review security issues",
                template = """Perform a security review of the following code:
                    |1. Check for OWASP Top 10 vulnerabilities (XSS, SQL injection, etc.)
                    |2. Identify potential command injection risks
                    |3. Check for path traversal vulnerabilities
                    |4. Verify input validation and sanitization
                    |5. Check for hardcoded secrets or credentials
                    |6. Review authentication and authorization logic
                    |
                    |Provide specific security issues found and recommendations.
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "security",
                isBuiltin = true
            )
        )
    }
}
