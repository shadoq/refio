package pl.jclab.refio.ui.settings

/**
 * Context-window sizes offered in Settings -> Providers, in tokens, per provider.
 *
 * One set per provider rather than one shared list: how large a window a runtime can serve is a
 * property of that runtime, so the sets have to be able to diverge without dragging the others
 * along. Adjust a single provider's bounds here when a concrete limit for it is known.
 *
 * A value outside a provider's set stays valid in `config.yaml` - none of the context-size config
 * keys has a validator - and the dropdown then shows the nearest lower offered size, see
 * [nearestNumericOption].
 */
internal object ContextSizeOptions {

    /** Ollama `num_ctx`. Bounded by the machine, not by the protocol. */
    val OLLAMA: List<String> = sizes()

    /** LM Studio's per-model context, set when the model is loaded there. */
    val LM_STUDIO: List<String> = sizes()

    /**
     * Any OpenAI-compatible server (llama.cpp, vLLM). These are routinely started with very large
     * windows, and `/v1/models` does not report the value, so the user declares it here.
     */
    val GENERIC_OPENAI: List<String> = sizes()

    /**
     * Sizes from [from] doubling up to [doubleUpTo], then growing by [step] up to [upTo].
     *
     * Doubling matches how small local runtimes are configured, but past a few hundred thousand
     * tokens it skips most of the usable range, hence the linear tail.
     */
    private fun sizes(
        from: Int = 2048,
        doubleUpTo: Int = 262_144,
        step: Int = 131_072,
        upTo: Int = 1_048_576,
    ): List<String> = buildList {
        var doubling = from
        while (doubling <= doubleUpTo) {
            add(doubling)
            doubling *= 2
        }
        var stepped = doubleUpTo + step
        while (stepped <= upTo) {
            add(stepped)
            stepped += step
        }
    }.map { it.toString() }
}
