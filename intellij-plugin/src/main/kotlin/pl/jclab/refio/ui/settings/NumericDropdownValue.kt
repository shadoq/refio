package pl.jclab.refio.ui.settings

/**
 * Picks the option a numeric dropdown should display for a configured value.
 *
 * A non-editable `JComboBox` silently rejects a selection that is not in its model, so a
 * configured value outside the offered set used to leave the field showing whatever it was
 * constructed with - misrepresenting the actual configuration. Provider context-size keys have
 * no validator, so such values are legitimate and reachable by hand-editing `config.yaml`.
 *
 * Returns the largest option not exceeding [requested]; the smallest option when [requested] is
 * below all of them. Non-numeric input and exact matches are returned unchanged, so this is safe
 * to call for every dropdown.
 *
 * Separated from the panel so it is testable without constructing Swing components, which the
 * plugin test source set cannot do (no IntelliJ platform test framework).
 */
internal fun nearestNumericOption(options: List<String>, requested: String): String {
    val requestedNumber = requested.toIntOrNull() ?: return requested
    val numericOptions = options.mapNotNull { it.toIntOrNull() }
    if (numericOptions.isEmpty() || numericOptions.contains(requestedNumber)) {
        return requested
    }

    val normalized = numericOptions.filter { it <= requestedNumber }.maxOrNull() ?: numericOptions.min()
    return normalized.toString()
}
