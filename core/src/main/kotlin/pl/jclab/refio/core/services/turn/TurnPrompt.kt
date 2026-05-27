package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.llm.LLMMessage

/**
 * Prompt handed from [TurnPromptBuilder] to [TurnLLMCaller] and back to the turn loop.
 *
 * Single shape for the whole turn path — previous revisions split this into
 * `TurnPrompt` / `LLMCallPrompt` / `CoreTurnPrompt` plus converters; all three carried the
 * same pair of fields. One class, no adapters.
 *
 * @property cacheableSystemLength character length of the stable prefix within
 *   [systemPrompt]. Providers that support prompt-prefix caching (currently the
 *   Anthropic adapter via the `cache_control` block marker) split the system
 *   prompt at this boundary and mark the prefix as cacheable. Null means the
 *   prompt is not split — no caching hint. Setting it equal to
 *   `systemPrompt.length` would mark everything cacheable but waste a control
 *   block on the trailing newline; better to compute it as the byte length of
 *   the joined stable sections only.
 */
data class TurnPrompt(
    val systemPrompt: String,
    val messages: List<LLMMessage>,
    val cacheableSystemLength: Int? = null
)
