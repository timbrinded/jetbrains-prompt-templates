package dev.timbrinded.prompttemplates.core

internal fun TemplateMetadata.withLiteralEnumChoices(): TemplateMetadata = copy(
    variables = variables.map { variable ->
        if (variable.type == PromptVariableType.ENUM) {
            variable.withLiteralEnumChoices()
        } else {
            variable.copy(options = emptyList())
        }
    },
)

private fun PromptVariable.withLiteralEnumChoices(): PromptVariable {
    val usedIds = mutableSetOf<String>()
    val usedChoices = mutableSetOf<String>()
    val literalOptions = buildList {
        options.forEachIndexed { index, option ->
            addChoice(option.label, option.id, index, usedIds, usedChoices)
            if (option.value != option.label) {
                addChoice(option.value, null, index, usedIds, usedChoices)
            }
        }
    }
    val normalizedDefault = defaultValue ?: literalOptions.firstOrNull()?.id
    return copy(
        required = true,
        defaultValue = normalizedDefault,
        options = literalOptions,
    )
}

private fun MutableList<EnumOption>.addChoice(
    rawChoice: String,
    preferredId: String?,
    index: Int,
    usedIds: MutableSet<String>,
    usedChoices: MutableSet<String>,
) {
    val choice = rawChoice.trim()
    if (choice.isEmpty() || !usedChoices.add(choice)) return

    val generatedId = choice.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "option-${index + 1}" }
    val baseId = preferredId?.takeIf(String::isNotBlank) ?: generatedId
    var id = baseId
    var suffix = 2
    while (!usedIds.add(id)) {
        id = "$baseId-$suffix"
        suffix++
    }
    add(EnumOption(id, choice, choice))
}
