package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.llm.LLMMessage

/**
 * Prompt handed from [TurnPromptBuilder] to [TurnLLMCaller] and back to the turn loop.
 *
 * Single shape for the whole turn path — previous revisions split this into
 * `TurnPrompt` / `LLMCallPrompt` / `CoreTurnPrompt` plus converters; all three carried the
 * same pair of fields. One class, no adapters.
 */
data class TurnPrompt(
    val systemPrompt: String,
    val messages: List<LLMMessage>
)
