package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.services.TurnPrompt as CoreTurnPrompt

/**
 * Turn prompt data class - aligned with core services.
 */
data class TurnPrompt(
    val systemPrompt: String,
    val messages: List<LLMMessage>
)

/**
 * Convert to core TurnPrompt for compatibility.
 */
fun TurnPrompt.toCoreTurnPrompt(): CoreTurnPrompt = CoreTurnPrompt(
    systemPrompt = systemPrompt,
    messages = messages
)

/**
 * Convert from core TurnPrompt.
 */
fun CoreTurnPrompt.toTurnPrompt(): TurnPrompt = TurnPrompt(
    systemPrompt = systemPrompt,
    messages = messages
)
