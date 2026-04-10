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
    val isBuiltin: Boolean = false,
    val showInEditor: Boolean = false
) {
    companion object {
        private val COMMON_RULES = """
            General rules:
            - First inspect the provided selection and infer conventions, naming, and patterns already used in the code.
            - Work only within the requested scope. Do not redesign unrelated parts of the system.
            - Preserve existing behavior unless the task explicitly asks for a behavior change.
            - If the selection is incomplete, add a short "Missing Context" section and continue with clearly marked assumptions.
            - Do not invent classes, APIs, libraries, project rules, or architecture that are not visible in the code.
            - Prefer concrete references to classes, methods, fields, DTOs, endpoints, files, and tables that appear in the selection.
            - For analysis tasks, clearly separate FACTS, ASSUMPTIONS, and RECOMMENDATIONS.
            - For code-generation tasks, return complete changed code, not pseudocode.
            - Keep the answer focused, practical, and directly usable by a developer or AI coding agent.
        """.trimIndent()

        private fun prompt(body: String): String = COMMON_RULES + "\n\n" + body.trimIndent()

        private val CREATE_AGENT_PROMPT = """
You are a prompt architect for Refio, an AI coding assistant platform. Your job is to help the user create a custom AI subagent by asking targeted questions, gathering context, and saving the result using the manage_subagent tool.

## Your Process

1. Start with intent: Ask what kind of agent the user wants to create and why
2. Ask questions iteratively: One question at a time, wait for answers, adapt based on responses
3. Be transparent: Explain you're building a subagent definition for Refio
4. Clarify when needed: If answers are vague or incomplete, ask follow-up questions
5. Infer patterns: Based on the domain and user's needs, identify relevant frameworks, techniques, and mental models
6. Synthesize: After gathering sufficient context (typically 8-12 questions), generate the final subagent

## Question Strategy

Your questions should gather:

### Factual context (use verbatim in final definition):
- Agent name (kebab-case, e.g. "api-tester", "docs-reviewer")
- Specific goals and responsibilities
- Target domain or codebase area
- Constraints or limitations

### Behavioral context (shape the system prompt):
- Desired interaction style (brief vs expansive, proactive vs reactive)
- Tone and voice preferences
- Level of expertise the agent should assume about the user
- How the agent should handle uncertainty

### Domain expertise (populate the system prompt):
- Relevant frameworks, methodologies, or mental models
- Tools, technologies, or platforms involved
- Industry-specific patterns or best practices
- Common pitfalls or anti-patterns to avoid

### Tool requirements (determine which Refio tools to enable):
Refio provides these tools. Ask which ones the agent needs:
- **Read-only tools:** read_file, grep_search, file_search, read_directory, view_diff
- **Write tools:** code_editing, create_new_file, multi_edit, multi_line_editor, advance_code_editing
- **Execution tools:** run_terminal_command, run_code, http_request
- **Delegation tools:** invoke_subagent, delegate_to_strong_model

Common patterns:
- Analysis/review agents: read_file, grep_search, file_search, read_directory
- Code modification agents: read_file, grep_search, file_search, code_editing, create_new_file
- Full development agents: all tools

### Execution configuration:
- **Execution mode:** "single_shot" (one response, good for analysis) or "multi_step" (iterative with tools, good for complex tasks)
- **Max steps:** 1-50 iterations (default: 25 for analysis, 50 for development)
- **Model preference:** "default", "coding", "plan", "weak", "inherit" (from parent), or specific model ID
- **Context profile:** Which context sections to include:
  - include_file_tree (default: true) - project structure
  - include_conversation (default: true) - conversation history
  - include_working_memory (default: true) - accumulated knowledge
  - include_rag (default: true) - semantic code search results
  - include_dependencies (default: true) - project dependencies

## Adapting to Domains

Adjust your questions based on the agent type:

**Programming/Technical:**
- Languages, frameworks, tech stack focus
- Code quality standards and conventions
- Testing philosophy (TDD, coverage targets)

**Documentation:**
- Audience level (beginner, intermediate, expert)
- Documentation format and style
- Cross-reference requirements

**Security/Audit:**
- Standards (OWASP, CIS, custom)
- Risk tolerance level
- Compliance requirements

**Research/Analysis:**
- Data sources and methodology
- Output format preferences
- Depth vs breadth trade-off

**Code Review:**
- Review strictness level
- Focus areas (performance, security, maintainability)
- Team conventions to enforce

## Prompt Engineering Techniques to Apply

When building the system prompt, strategically apply these based on domain:

- **Role Assignment:** Define specific expertise and professional identity
- **Step-by-Step Thinking:** For complex analysis tasks, instruct methodical approach
- **Uncertainty Acknowledgment:** "If uncertain, say so clearly"
- **Structured Output:** Use sections for organized responses
- **Scope Boundaries:** Define what the agent should NOT attempt
- **Clarifying Questions:** "Ask 2-3 targeted questions rather than guessing"

Select 5-10 techniques that fit the agent's purpose. Don't use all of them.

## System Prompt Structure

Generate system prompts using this structure (include only relevant sections):

```
You are [role description with specific expertise].

<rules>
- [Key behavioral rules based on user's requirements]
- Stay truthful. If unsure, say so clearly.
- [Domain-specific rules]
</rules>

<expertise>
[Relevant frameworks, methodologies, mental models, anti-patterns to avoid]
</expertise>

<main_objective>
[Clear statement of the agent's primary purpose and success criteria]
</main_objective>
```

## Saving the Agent

After gathering all information and confirming with the user, call the manage_subagent tool:

```
manage_subagent(
    action = "create",
    scope = "project",
    name = "<kebab-case-name>",
    description = "<one-line description, max 120 chars>",
    system_prompt = "<generated system prompt>",
    tools = [<selected tools>],
    model = "<chosen model alias>",
    max_steps = <chosen max steps>
)
```

## Behavioral Guidelines

- Ask ONE question at a time
- After each answer, briefly acknowledge it and explain how it shapes the agent
- Signal progress: "We're about halfway through" / "Just a couple more questions"
- If the user provides rich detail, adapt and skip redundant questions
- If the user is terse, fill in reasonable defaults and confirm
- Match the user's communication style
- NEVER guess critical details (name, tools, domain) — always confirm
- After generating, summarize what was created and how to invoke it: !agent-name

## Starting the Conversation

Begin with: "I'll help you create a custom subagent for Refio. What kind of agent do you want to build, and what should it help you with?"

Then adapt your questions based on their answer, always asking one at a time.
        """.trimIndent()

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
                description = "Explain what this code does, how it works, and what to watch out for",
                template = prompt("""
                    Goal: explain the selected code so that another developer can quickly understand what it does, why it exists, and what to be careful about.

                    Response format:

                    ## Purpose
                    Explain in 3-5 sentences:
                    - what this code is responsible for,
                    - where it fits in the system,
                    - what problem it solves.

                    ## How It Works
                    Walk through the main flow step by step.
                    For each important block, explain:
                    - what it does,
                    - what input it expects,
                    - what output or side effect it produces,
                    - why it is implemented this way.

                    ## Key Dependencies
                    List:
                    - internal dependencies,
                    - external libraries/services/APIs,
                    - important inputs/outputs,
                    - side effects such as DB writes, I/O, state changes, events, logging.

                    ## Important Design Choices
                    Point out:
                    - patterns or abstractions used,
                    - trade-offs,
                    - hidden assumptions,
                    - why some parts may look unusual.

                    ## Risks / Things to Watch
                    Highlight:
                    - edge cases,
                    - error handling gaps,
                    - concurrency concerns,
                    - maintainability concerns.

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "understanding",
                isBuiltin = true,
                showInEditor = true
            ),

            // =====================================================================
            // IMPROVEMENT
            // =====================================================================
            SlashCommand(
                id = "refactor",
                name = "refactor",
                description = "Suggest focused refactoring with concrete safe changes",
                template = prompt("""
                    Review the selected code and suggest focused refactoring improvements.

                    Rules:
                    - Do not change business behavior.
                    - Prefer small, safe refactorings over large rewrites.
                    - Respect existing project conventions unless they are clearly harmful.
                    - If the file/class is too large, suggest a decomposition plan rather than a blind split.

                    Check especially for:
                    1. Too many responsibilities in one class/function
                    2. Long methods with mixed levels of abstraction
                    3. Repeated logic
                    4. Poor naming
                    5. Excessive branching/nesting
                    6. Primitive obsession / data clumps
                    7. Tight coupling and hidden dependencies
                    8. Low testability

                    Response format:

                    ## Summary
                    Short verdict on the main refactoring opportunities.

                    ## Findings
                    For each finding provide:
                    - Problem
                    - Why it matters
                    - Recommended refactoring
                    - Expected benefit
                    - Refactoring safety level: Low / Medium / High risk

                    ## Suggested Order
                    List the refactorings in the safest execution order.

                    ## Example Changes
                    Show concrete before -> after code for the most important improvements.

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "improvement",
                isBuiltin = true,
                showInEditor = true
            ),

            SlashCommand(
                id = "simplify",
                name = "simplify",
                description = "Simplify code while preserving behavior",
                template = prompt("""
                    Simplify the selected code while preserving its behavior.

                    Focus on:
                    - reducing nesting,
                    - replacing complex conditionals with guard clauses / early returns / clearer branching,
                    - removing unnecessary temporary variables,
                    - removing dead code,
                    - simplifying boolean expressions,
                    - using Kotlin idioms only where they improve readability.

                    Do not:
                    - introduce clever but harder-to-read constructs,
                    - change public behavior,
                    - add unrelated abstractions.

                    Response format:

                    ## Simplification Opportunities
                    List the main complexity sources.

                    ## Simplified Code
                    Provide the updated code.

                    ## What Changed
                    For each meaningful change explain:
                    - what was simplified,
                    - why it is easier to read or maintain,
                    - why behavior stays the same.

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "improvement",
                isBuiltin = true,
                showInEditor = true
            ),

            SlashCommand(
                id = "optimize",
                name = "optimize",
                description = "Analyze performance and propose practical optimizations",
                template = prompt("""
                    Analyze the selected code for performance and propose practical optimizations.

                    Focus on:
                    - time complexity,
                    - memory allocations,
                    - repeated work,
                    - expensive I/O,
                    - unnecessary synchronization,
                    - poor data structure choices,
                    - blocking calls in hot paths.

                    Do not suggest speculative micro-optimizations with no clear value.

                    Response format:

                    ## Performance Summary
                    State where the likely cost is concentrated.

                    ## Findings
                    For each finding provide:
                    - Current issue
                    - Why it is expensive
                    - Estimated cost type: CPU / Memory / I/O / Contention
                    - Proposed optimization
                    - Expected impact
                    - Confidence: High / Medium / Low

                    ## Optimized Code
                    Provide improved code for the highest-value changes.

                    ## Validation
                    Explain how to verify that the optimization actually helps.

                    Selected code:
                    {selection}
                """),
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
                description = "Generate high-value unit tests",
                template = prompt("""
                    Generate high-value unit tests for the selected code.

                    Framework:
                    - JUnit 5
                    - MockK

                    Rules:
                    - Test the real behavior of the class under test.
                    - Mock only external collaborators.
                    - Prefer small, focused tests.
                    - Cover both expected behavior and important failure scenarios.
                    - If the code is hard to test, briefly explain why and suggest a small testability improvement.

                    Response format:

                    ## Test Plan
                    List the scenarios to cover:
                    - happy path,
                    - edge cases,
                    - invalid input,
                    - error propagation,
                    - side effects / interactions.

                    ## Test Code
                    Provide the complete test class.

                    ## Notes
                    Mention:
                    - what is intentionally not tested,
                    - any assumptions,
                    - any gaps caused by missing context.

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "testing",
                isBuiltin = true,
                showInEditor = true
            ),

            SlashCommand(
                id = "test-integration",
                name = "test-integration",
                description = "Generate integration tests for real component collaboration",
                template = prompt("""
                    Generate integration tests for the selected code.

                    Goal:
                    Test real collaboration between components, not isolated units.

                    Guidelines:
                    - Minimize mocking.
                    - Prefer real dependencies where practical.
                    - Use temporary filesystem or in-memory DB when relevant.
                    - Verify full flows: input -> processing -> persistence/output.
                    - Cover at least one failure scenario that crosses layer boundaries.

                    Response format:

                    ## Integration Scenarios
                    List the end-to-end interactions to validate.

                    ## Test Setup
                    Explain what real collaborators/resources are needed.

                    ## Test Code
                    Provide the complete integration test code.

                    ## Verification Notes
                    Explain what each test proves about the system behavior.

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "testing",
                isBuiltin = true
            ),

            SlashCommand(
                id = "test-edge-cases",
                name = "test-edge-cases",
                description = "Generate tests focused on edge cases and boundaries",
                template = prompt("""
                    Generate tests focused only on edge cases and boundary conditions.

                    Cover what is relevant from:
                    - null and missing values,
                    - empty vs blank vs single-item inputs,
                    - min/max/boundary numeric values,
                    - duplicates,
                    - unusual strings and Unicode,
                    - off-by-one conditions,
                    - concurrency/cancellation only if the selected code is actually concurrent.

                    Rules:
                    - One edge case per test where practical.
                    - Do not generate unrealistic test cases with no relation to the code.

                    Response format:
                    1. Brief list of edge cases
                    2. Complete test code
                    3. Short explanation of why each edge case matters

                    Selected code:
                    {selection}
                """),
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
                description = "Fix a bug with root cause analysis and regression check",
                template = prompt("""
                    Fix the bug, error, or warning in the selected code.

                    Goal:
                    Apply the smallest correct fix that addresses the root cause without creating unrelated changes.

                    Response format:

                    ## Root Cause
                    Explain the real cause, not just the symptom.

                    ## Reproduction
                    Describe what input/state/sequence triggers the issue.

                    ## Fix Strategy
                    Explain what will be changed and why this is the safest fix.

                    ## Code
                    Provide the corrected code.

                    ## Regression Test
                    Provide a test that would fail before the fix and pass after it.

                    ## Risk Check
                    Briefly mention what existing behavior could accidentally be affected.

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "fixing",
                isBuiltin = true,
                showInEditor = true
            ),

            // =====================================================================
            // DOCUMENTATION
            // =====================================================================
            SlashCommand(
                id = "document",
                name = "document",
                description = "Add useful KDoc and non-obvious inline documentation",
                template = prompt("""
                    Add useful documentation to the selected code.

                    Rules:
                    - Use English.
                    - Add KDoc for public classes, public methods, and non-obvious domain concepts.
                    - Document intent, important constraints, and side effects.
                    - Do not explain trivial syntax.
                    - Add inline comments only where the logic is non-obvious or surprising.

                    Response format:
                    1. Updated code with documentation added
                    2. Short note describing what was documented and what was intentionally left undocumented

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "documentation",
                isBuiltin = true,
                showInEditor = true
            ),

            // =====================================================================
            // TRANSLATION
            // =====================================================================
            SlashCommand(
                id = "translate-comments",
                name = "translate-comments",
                description = "Translate comments and docs to professional English",
                template = prompt("""
                    Translate all comments and documentation in the selected code to professional technical English.

                    Rules:
                    - Change comments only.
                    - Do not change logic, formatting placeholders, identifiers, or behavior.
                    - Preserve original meaning and tone where possible.
                    - Keep terminology consistent.

                    Response format:
                    Provide the updated code only.

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "translation",
                isBuiltin = true
            ),

            SlashCommand(
                id = "translate-messages",
                name = "translate-messages",
                description = "Translate user-facing messages and logs to English",
                template = prompt("""
                    Translate all user-facing string messages in the selected code to clear professional English.

                    Translate:
                    - error messages,
                    - warning messages,
                    - log messages,
                    - UI labels/text,
                    - status messages.

                    Rules:
                    - Preserve placeholders and formatting.
                    - Do not rename variables, methods, or classes.
                    - Keep wording concise and natural.

                    Response format:
                    Provide the updated code only.

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "translation",
                isBuiltin = true
            ),

            SlashCommand(
                id = "translate-all",
                name = "translate-all",
                description = "Translate all human-readable text in code to English",
                template = prompt("""
                    Translate all human-readable text in the selected code to English.

                    Include:
                    - comments,
                    - KDoc/JavaDoc,
                    - user-facing strings,
                    - log and error messages.

                    Rules:
                    - Preserve behavior and formatting.
                    - Do not rename public APIs unless explicitly necessary.
                    - If private identifiers are in a non-English language and seriously reduce readability, you may suggest better names in a short note instead of renaming automatically.

                    Response format:
                    1. Updated code
                    2. Optional short note with suggested identifier renames, if any

                    Selected code:
                    {selection}
                """),
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
                description = "Add useful structured logging without noise",
                template = prompt("""
                    Add useful logging to the selected code.

                    Project rules:
                    - Use dualLogger() for plugin code.
                    - Prefer structured, meaningful messages.
                    - Include identifiers, counts, durations, and important state transitions where helpful.
                    - Use INFO for major operations, DEBUG for detailed flow, ERROR for failures.
                    - Do not log secrets, full sensitive payloads, or noisy per-item logs in tight loops.

                    Response format:

                    ## Logging Plan
                    Briefly list where logging should be added and why.

                    ## Updated Code
                    Provide the updated code with logging added.

                    ## Notes
                    Mention any places where logging was intentionally avoided to reduce noise.

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "enhancement",
                isBuiltin = true
            ),

            SlashCommand(
                id = "add-error-handling",
                name = "add-error-handling",
                description = "Add meaningful error handling at real failure points",
                template = prompt("""
                    Add proper error handling to the selected code.

                    Focus on:
                    - likely failure points,
                    - useful exception messages,
                    - preserving root cause,
                    - avoiding silent failure,
                    - making failures diagnosable.

                    Rules:
                    - Add handling only where it improves correctness or observability.
                    - Do not wrap everything in generic try/catch.
                    - Log errors where the failure boundary is meaningful.
                    - Prefer explicit failure over hidden fallback behavior unless the code already defines a fallback strategy.

                    Response format:
                    1. Error handling risks found
                    2. Updated code
                    3. Short explanation of the handling strategy

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "enhancement",
                isBuiltin = true
            ),

            SlashCommand(
                id = "add-validation",
                name = "add-validation",
                description = "Add input validation at real system boundaries",
                template = prompt("""
                    Add input validation to the selected code.

                    Focus on validation at real boundaries such as:
                    - public API entry points,
                    - external input,
                    - file paths,
                    - parsed data,
                    - DTOs,
                    - user-provided values.

                    Rules:
                    - Add only meaningful validation.
                    - Use clear exception messages.
                    - Do not clutter deep internal code with repetitive checks if the boundary can guarantee validity.
                    - Reflect domain rules where visible in the code.

                    Response format:
                    1. Validation points identified
                    2. Updated code
                    3. Short explanation of what is validated and why

                    Selected code:
                    {selection}
                """),
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
                description = "Extract selected logic into a well-named method",
                template = prompt("""
                    Extract the selected code into a well-named method/function.

                    Goal:
                    Improve readability and separation of responsibilities without changing behavior.

                    Rules:
                    - Choose a name that describes intent, not mechanics.
                    - Pass only the parameters that are truly needed.
                    - Return only what the caller needs.
                    - Do not over-extract tiny trivial code.
                    - Keep the extracted method cohesive.

                    Response format:
                    1. Why extraction helps
                    2. Updated code
                    3. Short note about parameter and return design

                    Selected code:
                    {selection}
                """),
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
                description = "Review code for realistic security issues with severity",
                template = prompt("""
                    Perform a practical security review of the selected code.

                    Focus on realistic vulnerabilities relevant to the code, including:
                    - injection,
                    - auth/authz gaps,
                    - secret exposure,
                    - unsafe logging,
                    - insecure file/path handling,
                    - unsafe deserialization,
                    - weak crypto usage,
                    - missing validation at trust boundaries.

                    For each finding, provide:
                    - Severity: Critical / High / Medium / Low
                    - Category / CWE if reasonably known
                    - Location
                    - Why it is risky
                    - Likely attack path
                    - Recommended fix

                    Do not invent vulnerabilities without evidence.

                    Response format:

                    ## Security Summary
                    Brief overall assessment.

                    ## Findings
                    List findings in priority order.

                    ## Recommended Fix Order
                    State what should be fixed first.

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "security",
                isBuiltin = true,
                showInEditor = true
            ),

            SlashCommand(
                id = "threat-model",
                name = "threat-model",
                description = "Perform a lightweight STRIDE threat model",
                template = prompt("""
                    Perform a lightweight STRIDE threat model for the selected code or component.

                    Analyze:
                    - assets,
                    - actors,
                    - trust boundaries,
                    - entry points,
                    - sensitive operations,
                    - external integrations.

                    Then evaluate only the relevant STRIDE categories:
                    - Spoofing
                    - Tampering
                    - Repudiation
                    - Information Disclosure
                    - Denial of Service
                    - Elevation of Privilege

                    Response format:

                    ## Scope
                    What component is being modeled.

                    ## Assets / Actors / Trust Boundaries
                    Concise list.

                    ## Threats
                    For each relevant threat:
                    - STRIDE category
                    - Scenario
                    - Risk level
                    - Why it matters
                    - Suggested mitigation

                    ## Priority Actions
                    Top mitigations in recommended order.

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "security",
                isBuiltin = true
            ),

            SlashCommand(
                id = "security-fix",
                name = "security-fix",
                description = "Fix visible security issues with safe-by-default changes",
                template = prompt("""
                    Fix the security issues visible in the selected code.

                    Rules:
                    - Prefer the smallest secure change that removes the risk.
                    - Preserve intended behavior where possible.
                    - Use safe-by-default patterns.
                    - Do not leave partial or cosmetic fixes.

                    Apply secure coding rules where relevant:
                    - parameterize queries/commands,
                    - validate and sanitize external input,
                    - restrict file access appropriately,
                    - avoid logging secrets,
                    - preserve authorization boundaries,
                    - use secure comparisons/tokens/crypto primitives.

                    Response format:
                    1. Vulnerabilities addressed
                    2. Updated secure code
                    3. Why the fix is secure
                    4. Security regression test ideas

                    Selected code:
                    {selection}
                """),
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
                description = "Perform a deep code quality analysis",
                template = prompt("""
                    Perform a deep code quality analysis of the selected code.

                    Evaluate:
                    - complexity,
                    - coupling,
                    - cohesion,
                    - readability,
                    - maintainability,
                    - testability,
                    - naming quality,
                    - separation of concerns.

                    Do not try to produce fake exact metrics if the selection is too small. Use approximate reasoning where necessary and label it clearly.

                    Response format:

                    ## Overall Assessment
                    Grade the code quality and explain why.

                    ## Strengths
                    What is already good.

                    ## Problems
                    For each problem provide:
                    - issue,
                    - impact,
                    - evidence from the code,
                    - recommended improvement.

                    ## Priority Order
                    What should be improved first and why.

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "analysis",
                isBuiltin = true
            ),

            SlashCommand(
                id = "architecture",
                name = "architecture",
                description = "Review the architecture role, boundaries, and coupling",
                template = prompt("""
                    Perform an architectural review of the selected code.

                    Analyze:
                    - responsibility of this component,
                    - its place in the system/layering,
                    - dependencies in and out,
                    - data flow,
                    - state ownership,
                    - extension points,
                    - hidden assumptions,
                    - maintainability under future change.

                    Response format:

                    ## Role in the Architecture
                    What this component appears to be responsible for.

                    ## Architectural Strengths
                    What is structurally sound.

                    ## Architectural Concerns
                    Layering issues, coupling problems, unstable boundaries, mixed responsibilities, or poor extension points.

                    ## Recommendations
                    Prioritized improvements with rough effort: S / M / L.

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "analysis",
                isBuiltin = true
            ),

            SlashCommand(
                id = "implementation-analysis",
                name = "implementation-analysis",
                description = "Analyze a topic and prepare an implementation-ready markdown document",
                template = prompt("""
                    Conduct a practical implementation analysis for the provided topic using the selected code or context.

                    Goal:
                    Prepare an implementation-oriented document that helps a developer or a small AI coding agent understand:
                    - what the topic most likely means,
                    - what in the current system is relevant,
                    - what should be changed or added,
                    - how to implement it safely and incrementally.

                    Rules:
                    - First analyze the existing system/context, then propose changes.
                    - Clearly separate FACTS, ASSUMPTIONS, and RECOMMENDATIONS.
                    - If the topic is ambiguous, briefly list possible interpretations and choose the most likely one.
                    - Be concrete: refer to visible classes, files, endpoints, DTOs, tables, services, configs, and flows where possible.
                    - Explain not only what to change, but also why and how.
                    - Break bigger work into small implementation steps.
                    - Include impact on backend, frontend, database, API, integrations, security, performance, and tests when relevant.
                    - Write in a practical, instructional style suitable for a junior developer or a small AI agent.
                    - Return the result as Markdown. Save to file only if file-editing tools are available.

                    Required structure:

                    # Implementation Analysis: [topic]

                    ## 1. Goal of the change
                    ## 2. Topic interpretation
                    ## 3. Current system state
                    - FACTS
                    - ASSUMPTIONS

                    ## 4. Areas affected by the change
                    ## 5. Implementation analysis
                    ## 6. Solution variants
                    ## 7. Recommended implementation plan step by step
                    ## 8. Implementation instructions for a junior developer / small AI agent
                    ## 9. Impact on data and interfaces
                    ## 10. Risks and pitfalls
                    ## 11. Tests
                    ## 12. Open questions and assumptions
                    ## 13. Proposed work breakdown
                    ## 14. Implementation checklist
                    Use markdown checkboxes:
                    - [ ] task

                    Checklist sections should include, when relevant:
                    - Analysis and preparation
                    - Backend
                    - Frontend
                    - Database
                    - Integrations
                    - Tests
                    - Documentation and deployment

                    ## 15. Short delivery plan

                    Topic / context:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "analysis",
                isBuiltin = true
            ),

            SlashCommand(
                id = "code-review",
                name = "code-review",
                description = "Review code like a pull request",
                template = prompt("""
                    Review the selected code as if it were a pull request.

                    Focus on:
                    - correctness,
                    - readability,
                    - maintainability,
                    - performance,
                    - security,
                    - testability,
                    - consistency with project conventions.

                    Rules:
                    - Prefer concrete review comments over vague advice.
                    - Distinguish between must-fix issues and optional improvements.
                    - Do not nitpick style unless it meaningfully affects clarity or consistency.

                    Response format:

                    [line/block]: [MUST FIX / SHOULD FIX / NIT / QUESTION] comment

                    End with:
                    ## Review Summary
                    - Decision: Approve / Request Changes / Needs Discussion
                    - Top reasons for that decision

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "analysis",
                isBuiltin = true,
                showInEditor = true
            ),

            SlashCommand(
                id = "dependencies",
                name = "dependencies",
                description = "Analyze dependencies, hidden coupling, and decoupling options",
                template = prompt("""
                    Analyze the dependencies and coupling of the selected code.

                    Look for:
                    - direct dependencies,
                    - hidden dependencies,
                    - objects created inside the code,
                    - global/singleton access,
                    - dependency direction problems,
                    - tight coupling that hurts testing or changeability.

                    Response format:

                    ## Dependency Map
                    List major dependencies and how they are used.

                    ## Coupling Risks
                    For each risk provide:
                    - what is tightly coupled,
                    - why it is a problem,
                    - what kind of change becomes harder because of it.

                    ## Decoupling Suggestions
                    Provide practical ways to improve the design without overengineering.

                    Selected code:
                    {selection}
                """),
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
                description = "Implement a requested change with plan, code, and verification",
                template = prompt("""
                    Implement the requested feature or change based on the selected code and request.

                    Goal:
                    Produce a practical, minimal, working implementation that fits the existing codebase.

                    Implementation rules:
                    - First understand the current code and conventions.
                    - Break the work into small verifiable steps.
                    - Follow existing naming, architecture, and style unless they are clearly broken.
                    - Do not over-engineer.
                    - Change only what is needed for the requested feature.
                    - Keep behavior of unrelated parts unchanged.
                    - Use dualLogger() for plugin logging where relevant.
                    - Wrap DB operations in transaction {} where relevant.
                    - Use Dispatchers.IO for blocking I/O where relevant.
                    - Provide complete changed code, not pseudocode.
                    - If the request is underspecified, make the smallest reasonable assumptions and state them briefly.

                    Response format:

                    ## Plan
                    1. ...
                    2. ...
                    3. ...

                    ## Files Changed
                    List each created/modified file and why it changed.

                    ## Implementation
                    Provide the complete changed code.

                    ## Verification
                    Explain how to manually verify the behavior.

                    ## Tests
                    Provide or suggest the most important tests.

                    Selected code / request:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "implementation",
                isBuiltin = true
            ),

            SlashCommand(
                id = "migrate",
                name = "migrate",
                description = "Migrate code to a new API, pattern, or framework version",
                template = prompt("""
                    Migrate the selected code to a new API, pattern, or framework version.

                    Goal:
                    Preserve behavior while updating the implementation.

                    Rules:
                    - Identify what is old and what replaces it.
                    - Migrate incrementally.
                    - Avoid mixing migration with unrelated refactoring.
                    - Clearly call out breaking changes if they cannot be avoided.
                    - Remove obsolete code once the migration is complete.

                    Response format:

                    ## Migration Scope
                    What is being migrated.

                    ## Mapping
                    Old approach -> new approach

                    ## Updated Code
                    Provide complete updated code.

                    ## Verification
                    List the most important checks to ensure no regression.

                    Selected code:
                    {selection}
                """),
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
                description = "Review UI for usability, layout, and maintainability",
                template = prompt("""
                    Review the selected UI component from the perspective of usability, clarity, layout, and maintainability.

                    Check:
                    - layout quality,
                    - grouping and hierarchy,
                    - resize behavior,
                    - loading/empty/error states,
                    - keyboard accessibility,
                    - clarity of actions and labels,
                    - avoidable UX friction.

                    Response format:

                    ## UI Strengths
                    ## UI Problems
                    For each problem:
                    - what is wrong,
                    - why it hurts UX,
                    - how to improve it.

                    ## Priority Improvements
                    List the highest-value changes first.

                    Selected UI code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "ui",
                isBuiltin = true
            ),

            SlashCommand(
                id = "ui-improve",
                name = "ui-improve",
                description = "Improve a UI component with concrete code changes",
                template = prompt("""
                    Improve the selected UI component with concrete code changes.

                    Goal:
                    Make the UI clearer, easier to use, and more robust without changing unrelated functionality.

                    Focus on:
                    - visual hierarchy,
                    - spacing/grouping,
                    - action clarity,
                    - loading/error/empty states,
                    - resize behavior,
                    - keyboard/tab order where relevant,
                    - safer destructive actions where relevant.

                    Response format:
                    1. Main UX issues found
                    2. Updated code
                    3. Short explanation of the improvements

                    Selected UI code:
                    {selection}
                """),
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
                description = "Clean up code without changing behavior",
                template = prompt("""
                    Clean up the selected code without changing behavior.

                    Focus on:
                    - removing dead code,
                    - removing unused imports/variables/parameters,
                    - improving local naming where safe,
                    - formatting and structural cleanup,
                    - reducing redundancy,
                    - using straightforward Kotlin idioms where they improve clarity.

                    Rules:
                    - No behavior changes.
                    - No feature additions.
                    - No public API renames.
                    - No speculative refactoring.

                    Response format:
                    1. Cleanup summary
                    2. Updated code
                    3. Short note about any TODO/FIXME/HACK comments found

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "cleanup",
                isBuiltin = true
            ),

            SlashCommand(
                id = "decompose",
                name = "decompose",
                description = "Split a large file into smaller focused parts",
                template = prompt("""
                    Decompose the selected large file into smaller, focused parts.

                    Goal:
                    Improve maintainability and readability while preserving public behavior.

                    Rules:
                    - Identify natural responsibility boundaries.
                    - Prefer cohesive splits over equal-size splits.
                    - Preserve public API unless a change is explicitly justified.
                    - Avoid creating too many tiny files.
                    - Explain dependencies between extracted parts.

                    Response format:

                    ## Responsibilities Found
                    List the distinct responsibilities currently mixed together.

                    ## Proposed Split
                    For each new file:
                    - File name
                    - Responsibility
                    - Main contents
                    - Why it belongs there

                    ## Migration Plan
                    Step-by-step safe extraction order.

                    ## Risks
                    What could break during decomposition.

                    Selected code:
                    {selection}
                """),
                variables = listOf("selection"),
                category = "cleanup",
                isBuiltin = true
            ),

            // =====================================================================
            // CREATION
            // =====================================================================
            SlashCommand(
                id = "create-agent",
                name = "create-agent",
                description = "Create a custom AI subagent through guided conversation",
                template = CREATE_AGENT_PROMPT,
                variables = emptyList(),
                category = "creation",
                isBuiltin = true,
                showInEditor = false
            )
        )
    }
}