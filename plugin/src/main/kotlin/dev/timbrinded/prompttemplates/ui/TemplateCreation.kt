package dev.timbrinded.prompttemplates.ui

/** Match the repository's case-insensitive visible-name collision policy. Save rechecks the current folder. */
internal fun availableTemplateName(base: String, siblingNames: Collection<String>): String {
    val occupied = siblingNames.map(String::trim).toSortedSet(String.CASE_INSENSITIVE_ORDER)
    return generateSequence(1, Int::inc)
        .map { if (it == 1) base else "$base ($it)" }
        .first { it.trim() !in occupied }
}
