package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.EnumOption

internal fun parseEnumOptionInput(raw: String): List<EnumOption> {
    val used = mutableSetOf<String>()
    return raw.splitToSequence(';').map(String::trim).filter(String::isNotEmpty).distinct().mapIndexed { index, choice ->
        var id = choice.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "option-${index + 1}" }
        var suffix = 2
        val base = id
        while (!used.add(id)) {
            id = "$base-$suffix"
            suffix++
        }
        EnumOption(id, choice, choice)
    }.toList()
}
