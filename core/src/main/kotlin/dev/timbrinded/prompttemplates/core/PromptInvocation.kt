package dev.timbrinded.prompttemplates.core

/** One inspected render. Input maps are copied so provider or form changes cannot alter delivered text. */
class PromptInvocation(
    val stored: StoredTemplate,
    values: Map<String, String>,
    context: Map<String, ContextValue>,
) {
    val values: Map<String, String> = stored.template.metadata.variables.associate { variable ->
        variable.key to (values[variable.key] ?: variable.defaultValue
            ?: if (variable.type == PromptVariableType.ENUM) variable.options.firstOrNull()?.id.orEmpty() else "")
    }
    val context: Map<String, ContextValue> = context.toMap()
    val referencedContext: Set<String> = requestedContextKeys(stored.template)
    val render: RenderResult = StrictPromptRenderer().render(stored.template, this.values, this.context)

    fun withValue(key: String, value: String): PromptInvocation = PromptInvocation(stored, values + (key to value), context)

    fun resetValues(): PromptInvocation = PromptInvocation(stored, emptyMap(), context)
}

fun requestedContextKeys(template: PromptTemplate): Set<String> = LinearPlaceholderParser()
    .parse(template.markdown).placeholders
    .filter(PlaceholderToken::contextReference)
    .mapTo(linkedSetOf(), PlaceholderToken::key)

/** Show inputs in authored order, independently of placeholder order or repetition. */
fun referencedUserVariables(template: PromptTemplate): List<PromptVariable> {
    val keys = LinearPlaceholderParser().parse(template.markdown).placeholders
        .filterNot(PlaceholderToken::contextReference)
        .mapTo(hashSetOf(), PlaceholderToken::key)
    return template.metadata.variables.filter { it.key in keys }
}

/** Retain entered values only while their authored type and selected enum identity remain compatible. */
fun compatibleInvocationValues(
    previous: List<PromptVariable>,
    current: List<PromptVariable>,
    values: Map<String, String>,
): Map<String, String> {
    val previousByKey = previous.associateBy(PromptVariable::key)
    return current.mapNotNull { variable ->
        val value = values[variable.key] ?: return@mapNotNull null
        if (previousByKey[variable.key]?.type != variable.type) return@mapNotNull null
        if (variable.type == PromptVariableType.ENUM && variable.options.none { it.id == value }) return@mapNotNull null
        variable.key to value
    }.toMap()
}
