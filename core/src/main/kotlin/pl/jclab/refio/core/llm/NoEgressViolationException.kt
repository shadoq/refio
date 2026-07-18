package pl.jclab.refio.core.llm

/**
 * Exception thrown when no-egress mode is enabled and an attempt is made
 * to use a cloud LLM provider.
 *
 * No-egress mode blocks all external network calls to LLM providers,
 * allowing only local providers like Ollama.
 */
class NoEgressViolationException(message: String) : Exception(message)
