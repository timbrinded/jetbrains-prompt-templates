package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.EnumOption

internal fun parseEnumOptionInput(raw: String, previous: List<EnumOption> = emptyList()): List<EnumOption> {
    val previousByLabel = previous.associateBy(EnumOption::label)
    val reservedIds = previous.mapTo(mutableSetOf(), EnumOption::id)
    val used = mutableSetOf<String>()
    return raw.splitToSequence(';').map(String::trim).filter(String::isNotEmpty).distinct().mapIndexed { index, choice ->
        previousByLabel[choice]?.let { option ->
            if (used.add(option.id)) return@mapIndexed option
        }
        var id = choice.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "option-${index + 1}" }
        var suffix = 2
        val base = id
        while (id in reservedIds || !used.add(id)) {
            id = "$base-$suffix"
            suffix++
        }
        EnumOption(id, choice, choice)
    }.toList()
}
