package dev.timbrinded.prompttemplates.core

data class ReconciliationResult(
    val variables: List<PromptVariable>,
    val unusedKeys: Set<String>,
    val unknownContextKeys: Set<String>,
)

class TemplateReconciler(
    private val parser: PlaceholderParser = LinearPlaceholderParser(),
    private val knownContextKeys: Set<String> = BUILT_IN_CONTEXT_KEYS,
) {
    fun reconcile(markdown: String, existing: List<PromptVariable>): ReconciliationResult {
        val parsed = parser.parse(markdown)
        val existingByKey = existing.associateBy(PromptVariable::key)
        val discoveredUserKeys = parsed.placeholders
            .filterNot(PlaceholderToken::contextReference)
            .map(PlaceholderToken::key)
            .distinct()
        val discovered = discoveredUserKeys.filterNot(existingByKey::containsKey).map { key ->
            PromptVariable(key = key, label = defaultVariableLabel(key))
        }
        val unused = existing.filterNot { it.key in discoveredUserKeys }

        return ReconciliationResult(
            variables = existing + discovered,
            unusedKeys = unused.map(PromptVariable::key).toSet(),
            unknownContextKeys = parsed.placeholders
                .filter(PlaceholderToken::contextReference)
                .map(PlaceholderToken::key)
                .filterNot { it in knownContextKeys }
                .toSet(),
        )
    }

    fun rename(markdown: String, oldKey: String, newKey: String): String {
        require(USER_VARIABLE_KEY_REGEX.matches(newKey)) { "Invalid variable key '$newKey'." }
        val matches = parser.parse(markdown).placeholders.filter { it.key == oldKey }
        if (matches.isEmpty()) return markdown

        val result = StringBuilder(markdown)
        matches.asReversed().forEach { token ->
            result.replace(token.range.start, token.range.endExclusive, "{{$newKey}}")
        }
        return result.toString()
    }
}
