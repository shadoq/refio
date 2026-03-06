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
         * Built-in commands organized by category.
         */
        val BUILTINS = listOf(
            // =====================================================================
            // UNDERSTANDING
            // =====================================================================
            SlashCommand(
                id = "explain",
                name = "explain",
                description = "Explain code in depth",
                template = """Analyze and explain the following code. Structure your response as:
                    |
                    |## Summary
                    |One paragraph: what this code does and why it exists.
                    |
                    |## Detailed Walkthrough
                    |Step-by-step explanation of the logic flow. For each significant block:
                    |- What it does
                    |- Why it's done this way
                    |- What data flows in and out
                    |
                    |## Dependencies & Interactions
                    |- External dependencies (libraries, services, APIs)
                    |- Internal dependencies (other classes, functions called)
                    |- Side effects (state mutations, I/O, events emitted)
                    |
                    |## Patterns & Design Decisions
                    |- Design patterns used (and whether they're appropriate)
                    |- Key abstractions and their purpose
                    |- Trade-offs made in this implementation
                    |
                    |## Potential Issues
                    |- Edge cases not handled
                    |- Concurrency concerns
                    |- Error scenarios
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "understanding",
                isBuiltin = true
            ),

            // =====================================================================
            // IMPROVEMENT
            // =====================================================================
            SlashCommand(
                id = "refactor",
                name = "refactor",
                description = "Suggest refactoring with concrete changes",
                template = """Review the following code and suggest specific refactoring improvements.
                    |
                    |Rules:
                    |- Files should be 200-300 LOC max. If this file exceeds that, suggest extraction.
                    |- Apply SRP: each class/function should have one reason to change.
                    |- Apply KISS: remove unnecessary abstraction layers.
                    |- Prefer composition over inheritance.
                    |
                    |Check for these code smells:
                    |1. **God Class** - class doing too many things → suggest extract class
                    |2. **Long Method** (>30 lines) → suggest extract method
                    |3. **Feature Envy** - method using another class's data more than its own
                    |4. **Data Clumps** - same group of parameters repeated → suggest data class
                    |5. **Primitive Obsession** - raw types where value objects would help
                    |6. **Duplicate Code** - repeated logic → suggest shared function
                    |
                    |For each issue found, provide:
                    |- Problem description (1 line)
                    |- Concrete code change (before → after)
                    |- Why this improves the code
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
                template = """Simplify the following code while preserving functionality:
                    |1. Reduce nesting levels (max 2-3 levels deep)
                    |2. Replace complex conditionals with early returns or when expressions
                    |3. Replace verbose patterns with Kotlin idioms (let, run, also, takeIf)
                    |4. Remove dead code and unused variables
                    |5. Simplify boolean expressions
                    |
                    |Provide the simplified version with explanation of each change.
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "improvement",
                isBuiltin = true
            ),
            SlashCommand(
                id = "optimize",
                name = "optimize",
                description = "Optimize performance with analysis",
                template = """Analyze and optimize the following code for performance.
                    |
                    |## Analysis Required
                    |1. **Time Complexity**: State current Big-O for key operations
                    |2. **Space Complexity**: Memory allocation patterns, object creation in loops
                    |3. **I/O Bottlenecks**: Database calls, file reads, network requests in hot paths
                    |4. **Concurrency**: Thread safety issues, lock contention, unnecessary synchronization
                    |
                    |## Optimization Checklist
                    |- [ ] Unnecessary object allocations (especially in loops/streams)
                    |- [ ] N+1 query patterns or repeated DB calls
                    |- [ ] Missing caching for expensive computations
                    |- [ ] Blocking calls on UI/main thread
                    |- [ ] Collections: wrong data structure choice (List vs Set vs Map)
                    |- [ ] String concatenation in loops (use StringBuilder)
                    |- [ ] Lazy initialization opportunities
                    |
                    |For each optimization:
                    |1. Current complexity / cost
                    |2. Proposed change with code
                    |3. Expected improvement (quantified if possible)
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "improvement",
                isBuiltin = true
            ),

            // =====================================================================
            // TESTING
            // =====================================================================
            SlashCommand(
                id = "test",
                name = "test",
                description = "Generate comprehensive unit tests",
                template = """Generate comprehensive unit tests for the following code.
                    |
                    |## Framework
                    |Use JUnit 5 + MockK. Follow given/when/then structure in each test.
                    |
                    |## Test Categories (generate tests for each):
                    |
                    |### Happy Path
                    |- Normal inputs producing expected outputs
                    |- Standard use cases the code is designed for
                    |
                    |### Edge Cases
                    |- Empty collections, empty strings, zero values
                    |- Boundary values (min, max, off-by-one)
                    |- Null inputs (where nullable)
                    |- Single-element collections
                    |
                    |### Error Paths
                    |- Invalid inputs → expected exceptions
                    |- External service failures (mock throwing exceptions)
                    |- Timeout scenarios
                    |
                    |### State Verification
                    |- Side effects (verify mock interactions with `verify { }`)
                    |- State changes after method calls
                    |
                    |## Conventions
                    |- Test class name: `{ClassName}Test`
                    |- Test method names: `should {expected behavior} when {condition}`
                    |- One assertion per test (prefer)
                    |- Use `@Nested` inner classes to group related tests
                    |- Use `@BeforeEach` for common setup
                    |- Mock external dependencies, don't mock the class under test
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "testing",
                isBuiltin = true
            ),
            SlashCommand(
                id = "test-integration",
                name = "test-integration",
                description = "Generate integration tests",
                template = """Generate integration tests for the following code.
                    |
                    |## Framework
                    |Use JUnit 5. Minimize mocking — test real interactions between components.
                    |
                    |## Guidelines
                    |1. Use `@TempDir` for filesystem operations
                    |2. Use real database (in-memory SQLite) when testing DB interactions
                    |3. Test the full flow: input → processing → output/side effects
                    |4. Test component interactions, not just individual units
                    |5. Verify data persistence (write → read back → compare)
                    |6. Test error propagation across layers
                    |
                    |## Structure
                    |- Set up real dependencies in `@BeforeEach`
                    |- Clean up resources in `@AfterEach`
                    |- Group by scenario using `@Nested` classes
                    |- Name: `should {outcome} when {full scenario description}`
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "testing",
                isBuiltin = true
            ),
            SlashCommand(
                id = "test-edge-cases",
                name = "test-edge-cases",
                description = "Generate edge case tests",
                template = """Generate tests focused exclusively on edge cases and boundary conditions.
                    |
                    |## Categories to Cover
                    |
                    |### Boundary Values
                    |- Min/max integer, long, double values
                    |- Empty string vs blank string vs null
                    |- Empty collections vs single-element vs very large collections
                    |- Off-by-one: first element, last element, index 0, size-1
                    |
                    |### Null & Missing Data
                    |- Nullable parameters with null values
                    |- Optional fields absent
                    |- Default values applied correctly
                    |
                    |### Concurrency
                    |- Concurrent access to shared state
                    |- Race conditions in async operations
                    |- Coroutine cancellation handling
                    |
                    |### Unusual Input
                    |- Unicode, special characters, very long strings
                    |- Negative numbers where positive expected
                    |- Duplicate entries in collections
                    |- Circular references (if applicable)
                    |
                    |Use JUnit 5 + MockK. Each test should target exactly one edge case.
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "testing",
                isBuiltin = true
            ),

            // =====================================================================
            // FIXING
            // =====================================================================
            SlashCommand(
                id = "fix",
                name = "fix",
                description = "Fix bugs with root cause analysis",
                template = """Fix the following errors, warnings, or bugs.
                    |
                    |## Analysis Steps
                    |1. **Root Cause**: Identify the underlying cause, not just the symptom
                    |2. **Reproduction**: Describe what triggers this bug (input, state, sequence)
                    |3. **Impact**: What breaks when this bug occurs?
                    |
                    |## Fix Requirements
                    |4. **Minimal Fix**: Change the least amount of code necessary
                    |5. **No Side Effects**: Ensure the fix doesn't break existing functionality
                    |6. **Regression Prevention**: Suggest a test case that would catch this bug
                    |
                    |## Response Format
                    |```
                    |Root Cause: [1-2 sentences]
                    |Fix: [description of change]
                    |Code: [the actual fix]
                    |Test: [test case to prevent regression]
                    |```
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "fixing",
                isBuiltin = true
            ),

            // =====================================================================
            // DOCUMENTATION
            // =====================================================================
            SlashCommand(
                id = "document",
                name = "document",
                description = "Add documentation",
                template = """Add documentation for the following code:
                    |1. Add KDoc comments for classes and public functions
                    |2. Document parameters, return values, and thrown exceptions
                    |3. Add inline comments only for non-obvious logic
                    |4. Focus on 'why' not 'what' — don't document obvious things
                    |5. Use English language for all documentation
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "documentation",
                isBuiltin = true
            ),

            // =====================================================================
            // TRANSLATION
            // =====================================================================
            SlashCommand(
                id = "translate-comments",
                name = "translate-comments",
                description = "Translate comments to English",
                template = """Translate all comments in the following code to English:
                    |1. Translate code comments (single-line and multi-line)
                    |2. Translate KDoc/JavaDoc documentation
                    |3. Preserve code functionality — only change comments
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

            // =====================================================================
            // ENHANCEMENT
            // =====================================================================
            SlashCommand(
                id = "add-logging",
                name = "add-logging",
                description = "Add logging statements",
                template = """Add appropriate logging to the following code:
                    |1. Use dualLogger() for plugin code (outputs to IDE log and UI)
                    |2. Add INFO level for important operations and state transitions
                    |3. Add DEBUG level for detailed execution flow
                    |4. Add ERROR level for exceptions and failures (include exception object)
                    |5. Include meaningful context in log messages (IDs, counts, durations)
                    |6. Avoid excessive logging — don't log inside tight loops
                    |7. Use lazy logging: `logger.debug { "message ${'$'}var" }`
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
                    |1. Identify potential failure points (I/O, parsing, external calls)
                    |2. Add try-catch blocks where appropriate
                    |3. Throw meaningful exceptions with clear messages
                    |4. Never silently swallow errors
                    |5. Add logging for errors using dualLogger()
                    |6. Fail explicitly — no silent fallbacks
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

            // =====================================================================
            // REFACTORING
            // =====================================================================
            SlashCommand(
                id = "extract-method",
                name = "extract-method",
                description = "Extract method/function",
                template = """Extract the selected code into a separate method/function:
                    |1. Create a well-named function that describes what it does
                    |2. Identify and pass necessary parameters
                    |3. Determine appropriate return type
                    |4. Replace original code with function call
                    |5. Ensure the extraction improves code readability
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "refactoring",
                isBuiltin = true
            ),

            // =====================================================================
            // SECURITY
            // =====================================================================
            SlashCommand(
                id = "security-review",
                name = "security-review",
                description = "Deep security review with severity ratings",
                template = """Perform a thorough security review of the following code.
                    |
                    |## Check for Each Vulnerability Class
                    |
                    |### Injection (CWE-74)
                    |- SQL injection (raw queries, string concatenation in SQL)
                    |- Command injection (ProcessBuilder, Runtime.exec with user input)
                    |- Path traversal (user input in file paths without sanitization)
                    |- Log injection (unsanitized data in log messages)
                    |
                    |### Authentication & Access Control (CWE-284)
                    |- Missing authorization checks
                    |- Hardcoded credentials or API keys
                    |- Insecure token handling
                    |
                    |### Data Exposure (CWE-200)
                    |- Sensitive data in logs, error messages, or exceptions
                    |- Secrets in source code
                    |- Excessive data returned to client
                    |
                    |### Cryptography (CWE-310)
                    |- Weak algorithms (MD5, SHA1 for security)
                    |- Hardcoded keys or IVs
                    |- Insecure random number generation
                    |
                    |### Input Handling (CWE-20)
                    |- Missing input validation at boundaries
                    |- Deserialization of untrusted data
                    |- Integer overflow/underflow
                    |
                    |## Response Format (for each finding)
                    |```
                    |[CRITICAL/HIGH/MEDIUM/LOW] CWE-XXX: Title
                    |Location: line/method
                    |Issue: Description of the vulnerability
                    |Exploit: How an attacker could exploit this
                    |Fix: Secure code replacement
                    |```
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "security",
                isBuiltin = true
            ),
            SlashCommand(
                id = "threat-model",
                name = "threat-model",
                description = "STRIDE threat modeling",
                template = """Perform STRIDE threat modeling on the following code/component.
                    |
                    |## 1. Asset Identification
                    |- What data does this code process, store, or transmit?
                    |- What are the trust boundaries?
                    |- Who are the actors (users, services, external systems)?
                    |
                    |## 2. STRIDE Analysis
                    |For each relevant threat:
                    |
                    |### Spoofing (Identity)
                    |Can an attacker pretend to be someone else?
                    |
                    |### Tampering (Data Integrity)
                    |Can an attacker modify data in transit or at rest?
                    |
                    |### Repudiation (Non-repudiation)
                    |Can actions be denied? Is there audit logging?
                    |
                    |### Information Disclosure
                    |Can sensitive data leak through errors, logs, or side channels?
                    |
                    |### Denial of Service
                    |Can the system be overwhelmed? Resource exhaustion vectors?
                    |
                    |### Elevation of Privilege
                    |Can a low-privilege user gain higher access?
                    |
                    |## 3. Mitigations
                    |For each threat found, provide:
                    |- Risk level (Critical/High/Medium/Low)
                    |- Specific mitigation with code example
                    |- Priority order for implementation
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "security",
                isBuiltin = true
            ),
            SlashCommand(
                id = "security-fix",
                name = "security-fix",
                description = "Fix security vulnerabilities",
                template = """Fix security vulnerabilities in the following code.
                    |
                    |For each vulnerability found:
                    |1. Identify the security issue and its CWE classification
                    |2. Explain the attack vector (how it could be exploited)
                    |3. Provide the secure replacement code
                    |4. Explain why the fix is secure
                    |
                    |## Secure Coding Rules to Apply
                    |- Parameterize all queries (no string concatenation for SQL/commands)
                    |- Validate and sanitize all external input
                    |- Use PathSandbox for file operations (project root restriction)
                    |- Never log secrets or full stack traces to users
                    |- Use constant-time comparison for security tokens
                    |- Apply principle of least privilege
                    |
                    |Provide the complete fixed code, not just snippets.
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "security",
                isBuiltin = true
            ),

            // =====================================================================
            // ANALYSIS
            // =====================================================================
            SlashCommand(
                id = "analyze",
                name = "analyze",
                description = "Deep code quality analysis",
                template = """Perform a deep quality analysis of the following code.
                    |
                    |## Metrics
                    |- **Cyclomatic Complexity**: Count decision points per method (aim for <10)
                    |- **Coupling**: How many external classes/services does this depend on?
                    |- **Cohesion**: Do all methods relate to the class's single purpose?
                    |- **LOC**: Lines of code per class/method (target: class <300, method <30)
                    |
                    |## Code Smells
                    |Check for: God Class, Long Method, Feature Envy, Data Clumps,
                    |Primitive Obsession, Shotgun Surgery, Parallel Inheritance,
                    |Speculative Generality, Dead Code, Comments as Deodorant.
                    |
                    |## SOLID Compliance
                    |- **S**ingle Responsibility: Does each class have one reason to change?
                    |- **O**pen/Closed: Can behavior be extended without modification?
                    |- **L**iskov Substitution: Can subtypes replace their base types?
                    |- **I**nterface Segregation: Are interfaces focused and minimal?
                    |- **D**ependency Inversion: Does code depend on abstractions?
                    |
                    |## Response Format
                    |```
                    |Overall Quality: [A/B/C/D/F]
                    |Top Issues (priority order):
                    |1. [Issue] - [Impact] - [Suggested fix]
                    |2. ...
                    |```
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "analysis",
                isBuiltin = true
            ),
            SlashCommand(
                id = "architecture",
                name = "architecture",
                description = "Architectural review of module/component",
                template = """Perform an architectural review of the following code.
                    |
                    |## Layer Analysis
                    |- What architectural layer does this belong to? (UI / Service / API / DB / Core)
                    |- Does it correctly depend only on lower layers?
                    |- Are there any layer violations (e.g., DB code in UI layer)?
                    |
                    |## Data Flow
                    |- Trace the data flow: input → processing → output
                    |- Identify transformation points
                    |- Where does state live? Is it mutable or immutable?
                    |
                    |## Design Patterns
                    |- What patterns are used? Are they appropriate?
                    |- Suggest alternative patterns if current ones are problematic
                    |
                    |## Scalability & Maintainability
                    |- How hard is it to add new features to this component?
                    |- What would break if requirements change?
                    |- Are there hidden assumptions or magic numbers?
                    |
                    |## Recommendations
                    |Prioritized list of architectural improvements with effort estimates (S/M/L).
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "analysis",
                isBuiltin = true
            ),
            SlashCommand(
                id = "implementation-analysis",
                name = "implementation-analysis",
                description = "Prepare a practical implementation analysis document",
                template = """Conduct a system analysis for implementing the provided topic.
                    |
                    |The goal is to prepare a practical document that:
                    |- analyzes the current state of the system,
                    |- identifies what needs to be changed or added,
                    |- explains how to implement it,
                    |- is understandable for a junior developer or a simple AI assistant,
                    |- includes a checklist to track implementation progress.
                    |
                    |## Rules
                    |- First analyze the system, then recommend changes.
                    |- Clearly separate: FACTS, ASSUMPTIONS, RECOMMENDATIONS.
                    |- Do not guess without evidence.
                    |- Refer to concrete system elements when they are available.
                    |- Explain not only what to change, but also why and how.
                    |- Break large changes into small steps.
                    |- Include impact on backend, frontend, database, API, integrations, security, performance, and tests when the topic requires it.
                    |
                    |## Prepare the result using this structure
                    |1. Goal of the change
                    |2. Topic interpretation
                    |3. Current system state
                    |4. Areas affected by the change
                    |5. Implementation analysis
                    |6. Solution variants
                    |7. Recommended implementation approach step by step
                    |8. Implementation instructions for a junior developer / small AI agent
                    |9. Impact on data and interfaces
                    |10. Risks and pitfalls
                    |11. Tests
                    |12. Open questions and assumptions
                    |13. Proposed work breakdown
                    |14. Implementation checklist in markdown checkbox format
                    |15. Short delivery plan
                    |
                    |## Checklist requirements
                    |The checklist must be divided into sections and use this format:
                    |- [ ] task
                    |
                    |Include at least:
                    |- Analysis and preparation
                    |- Backend
                    |- Frontend
                    |- Database
                    |- Integrations
                    |- Tests
                    |- Documentation and deployment
                    |
                    |## Response style
                    |Write clearly, concretely, and practically.
                    |Create a document that a junior developer or a simple AI agent can use to start implementation.
                    |
                    |## Result
                    |Save the result as a text file in markdown format.
                    |
                    |TOPIC TO ANALYZE:
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "analysis",
                isBuiltin = true
            ),
            SlashCommand(
                id = "code-review",
                name = "code-review",
                description = "Full code review (as on a PR)",
                template = """Review the following code as if reviewing a pull request.
                    |
                    |## Review Checklist
                    |
                    |### Correctness
                    |- Does the code do what it claims to do?
                    |- Are there logic errors or off-by-one mistakes?
                    |- Are all code paths handled (including error paths)?
                    |
                    |### Code Style
                    |- Follows Kotlin idioms and conventions?
                    |- Naming is clear and consistent?
                    |- No unnecessary complexity?
                    |
                    |### Performance
                    |- Any obvious performance issues?
                    |- Unnecessary allocations or computations?
                    |- N+1 patterns?
                    |
                    |### Security
                    |- Input validated at boundaries?
                    |- No hardcoded secrets?
                    |- Safe handling of user data?
                    |
                    |### Testing
                    |- Is this code testable?
                    |- What tests are missing?
                    |
                    |### Maintainability
                    |- Would a new developer understand this easily?
                    |- Are there hidden dependencies or side effects?
                    |
                    |## Response Format
                    |Use inline comments format:
                    |```
                    |[line/block]: [MUST FIX / SHOULD FIX / NIT / QUESTION] description
                    |```
                    |
                    |End with a summary: Approve / Request Changes / Needs Discussion.
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "analysis",
                isBuiltin = true
            ),
            SlashCommand(
                id = "dependencies",
                name = "dependencies",
                description = "Analyze dependencies and coupling",
                template = """Analyze the dependencies and coupling of the following code.
                    |
                    |## Dependency Map
                    |List all dependencies:
                    |- **Imports**: What does this file import?
                    |- **Injected**: What is passed via constructor/parameters?
                    |- **Created**: What does this code instantiate directly?
                    |- **Global**: What static/singleton/companion objects are accessed?
                    |
                    |## Coupling Analysis
                    |- **Afferent Coupling (Ca)**: How many other modules depend on this?
                    |- **Efferent Coupling (Ce)**: How many modules does this depend on?
                    |- **Instability (I = Ce/(Ca+Ce))**: 0 = stable, 1 = unstable
                    |
                    |## Problems to Find
                    |1. Circular dependencies (A → B → A)
                    |2. Dependency Inversion violations (high-level depends on low-level concretions)
                    |3. Hidden dependencies (service locator, global state)
                    |4. Tight coupling (hard to test or replace a dependency)
                    |
                    |## Recommendations
                    |For each problem: suggest how to decouple (interface extraction, DI, events).
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "analysis",
                isBuiltin = true
            ),

            // =====================================================================
            // IMPLEMENTATION
            // =====================================================================
            SlashCommand(
                id = "implement",
                name = "implement",
                description = "Implement a feature with plan",
                template = """Implement the requested feature/change for the following code.
                    |
                    |## Process
                    |1. **Understand**: Analyze the existing code structure and patterns
                    |2. **Plan**: Break the implementation into small, verifiable steps
                    |3. **Implement**: Write the code following existing conventions
                    |4. **Verify**: Describe how to test the changes
                    |
                    |## Implementation Rules
                    |- Follow existing code style and patterns in the project
                    |- Keep files under 300 LOC — split if needed
                    |- Use dualLogger() for any logging
                    |- Wrap DB operations in transaction {}
                    |- Use Dispatchers.IO for blocking I/O
                    |- Don't over-engineer — implement exactly what's requested
                    |- Provide complete, runnable code (not pseudocode)
                    |
                    |## Response Format
                    |```
                    |## Plan
                    |1. [Step 1]
                    |2. [Step 2]
                    |...
                    |
                    |## Implementation
                    |[Complete code for each file changed/created]
                    |
                    |## Testing
                    |[How to verify this works]
                    |```
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "implementation",
                isBuiltin = true
            ),
            SlashCommand(
                id = "migrate",
                name = "migrate",
                description = "Migrate code to new pattern/API",
                template = """Migrate the following code to a new pattern, API, or framework version.
                    |
                    |## Migration Steps
                    |1. **Identify** all usages of the old pattern/API in the code
                    |2. **Map** old API → new API equivalents
                    |3. **Transform** code preserving behavior (no functional changes)
                    |4. **Verify** no regressions — list what to test
                    |
                    |## Rules
                    |- Preserve all existing behavior exactly
                    |- Migrate incrementally — don't change everything at once
                    |- Mark any breaking changes clearly
                    |- Remove old code completely (no backward-compat shims)
                    |- Update imports and type references
                    |
                    |## Response Format
                    |For each changed file:
                    |```
                    |File: [path]
                    |Change: [old pattern] → [new pattern]
                    |Code: [complete updated code]
                    |```
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "implementation",
                isBuiltin = true
            ),

            // =====================================================================
            // UI
            // =====================================================================
            SlashCommand(
                id = "ui-review",
                name = "ui-review",
                description = "Review UI",
                template = """Review the following UI/Swing component.
                    |
                    |## Layout & Structure
                    |- Is the layout manager appropriate?
                    |- Are components properly nested and grouped?
                    |- Does the layout handle window resizing correctly?
                    |- Are there hardcoded sizes that should be dynamic?
                    |
                    |## UX Patterns
                    |- Is the interaction model intuitive?
                    |- Are there loading states for async operations?
                    |- Are error states communicated to the user?
                    |- Is keyboard navigation supported?
                    |- Are tooltips provided for non-obvious controls?
                    |
                    |## Recommendations
                    |Prioritized list of improvements.
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "ui",
                isBuiltin = true
            ),
            SlashCommand(
                id = "ui-improve",
                name = "ui-improve",
                description = "Improve UI component design",
                template = """Improve the following UI component's design and user experience.
                    |
                    |## Areas to Improve
                    |
                    |### Visual Hierarchy
                    |- Are the most important elements visually prominent?
                    |- Is there proper spacing and grouping?
                    |- Are fonts and sizes consistent with conventions?
                    |
                    |### Interaction Design
                    |- Are action buttons clearly labeled and positioned?
                    |- Is there feedback for user actions (hover, click, loading)?
                    |- Are destructive actions protected (confirmation dialog)?
                    |- Does Tab order make sense?
                    |
                    |### Responsiveness
                    |- Does it look good at different panel sizes?
                    |- Do scrollable areas scroll properly?
                    |- Are minimum sizes set to prevent layout collapse?
                    |
                    |### Polish
                    |- Consistent margins and padding
                    |- Proper alignment of labels and inputs
                    |- Empty states handled (empty list, no data)
                    |
                    |Provide concrete code changes for each improvement.
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "ui",
                isBuiltin = true
            ),

            // =====================================================================
            // CLEANUP
            // =====================================================================
            SlashCommand(
                id = "clean",
                name = "clean",
                description = "Clean up code",
                template = """Clean up the following code without changing its behavior.
                    |
                    |## Cleanup Checklist
                    |1. **Dead Code**: Remove unused variables, functions, imports, parameters
                    |2. **TODOs**: List all TODO/FIXME/HACK comments found
                    |3. **Formatting**: Fix inconsistent indentation, spacing, line breaks
                    |4. **Naming**: Fix inconsistent naming (camelCase for Kotlin, UPPER_SNAKE for constants)
                    |5. **Kotlin Idioms**: Replace Java-style code with Kotlin idioms where natural
                    |6. **Redundancy**: Remove redundant type declarations, unnecessary casts, double negations
                    |7. **Organization**: Group related functions/properties together
                    |
                    |## Rules
                    |- Do NOT change any behavior or logic
                    |- Do NOT add new features or error handling
                    |- Do NOT rename public APIs (only private internals)
                    |- Provide the complete cleaned file
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "cleanup",
                isBuiltin = true
            ),
            SlashCommand(
                id = "decompose",
                name = "decompose",
                description = "Split large file into smaller ones",
                template = """This file is too large. Decompose it into smaller, focused files (target: 200-300 LOC each).
                    |
                    |## Analysis Steps
                    |1. **Identify Responsibilities**: List each distinct responsibility in this file
                    |2. **Group Related Code**: Which functions/classes belong together?
                    |3. **Find Boundaries**: Where are the natural split points?
                    |4. **Plan Dependencies**: What interfaces/contracts connect the pieces?
                    |
                    |## Decomposition Rules
                    |- Each new file should have a single, clear responsibility
                    |- Target 200-300 LOC per file
                    |- Extract data classes to their own files
                    |- Extract utility/helper functions to *Utils or *Extensions files
                    |- Keep the original file as a thin facade if needed
                    |- Maintain all public API signatures (no breaking changes)
                    |
                    |## Response Format
                    |```
                    |## Proposed Split
                    |
                    |### File 1: [FileName.kt] (~XXX LOC)
                    |Responsibility: [description]
                    |Contains: [list of classes/functions]
                    |
                    |### File 2: [FileName.kt] (~XXX LOC)
                    |...
                    |
                    |## Migration Steps
                    |1. [step]
                    |2. [step]
                    |...
                    |```
                    |
                    |{selection}""".trimMargin(),
                variables = listOf("selection"),
                category = "cleanup",
                isBuiltin = true
            )
        )
    }
}
