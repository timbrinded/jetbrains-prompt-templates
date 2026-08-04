package dev.timbrinded.prompttemplates.core

data class OutputMapping(
    val source: SourceRange,
    val output: SourceRange,
    val key: String?,
)

data class RenderResult(
    val renderedText: String,
    val diagnostics: List<TemplateDiagnostic>,
    val mappings: List<OutputMapping>,
) {
    val isValid: Boolean
        get() = diagnostics.none { it.severity == DiagnosticSeverity.ERROR }
}

interface PromptRenderer {
    fun render(
        template: PromptTemplate,
        userValues: Map<String, String>,
        contextValues: Map<String, ContextValue>,
    ): RenderResult
}

class StrictPromptRenderer(
    private val parser: PlaceholderParser = LinearPlaceholderParser(),
) : PromptRenderer {
    override fun render(
        template: PromptTemplate,
        userValues: Map<String, String>,
        contextValues: Map<String, ContextValue>,
    ): RenderResult {
        val parseResult = parser.parse(template.markdown)
        val variablesByKey = template.metadata.variables.associateBy(PromptVariable::key)
        val diagnostics = mutableListOf<TemplateDiagnostic>()
        diagnostics += parseResult.diagnostics

        val replacements = mutableListOf<Replacement>()
        parseResult.escapedOpenings.forEach { range ->
            replacements += Replacement(range, "{{", null)
        }

        parseResult.placeholders.forEach { token ->
            val replacement = when {
                token.contextReference -> resolveContext(token, contextValues, diagnostics)
                else -> resolveUserVariable(token, variablesByKey, userValues, diagnostics)
            }
            replacements += Replacement(token.range, replacement, token.key)
        }

        val referencedUserKeys = parseResult.placeholders
            .filterNot(PlaceholderToken::contextReference)
            .map(PlaceholderToken::key)
            .toSet()
        template.metadata.variables
            .filterNot { it.key in referencedUserKeys }
            .forEach { diagnostics += TemplateDiagnostic.UnusedVariableDefinition(it.key) }

        val output = StringBuilder(template.markdown.length)
        val mappings = mutableListOf<OutputMapping>()
        var sourceCursor = 0
        replacements.sortedBy { it.range.start }.forEach { replacement ->
            output.append(template.markdown, sourceCursor, replacement.range.start)
            val outputStart = output.length
            output.append(replacement.value)
            mappings += OutputMapping(
                source = replacement.range,
                output = SourceRange(outputStart, output.length),
                key = replacement.key,
            )
            sourceCursor = replacement.range.endExclusive
        }
        output.append(template.markdown, sourceCursor, template.markdown.length)

        return RenderResult(output.toString(), diagnostics, mappings)
    }

    private fun resolveUserVariable(
        token: PlaceholderToken,
        variablesByKey: Map<String, PromptVariable>,
        userValues: Map<String, String>,
        diagnostics: MutableList<TemplateDiagnostic>,
    ): String {
        val variable = variablesByKey[token.key]
        if (variable == null) {
            diagnostics += TemplateDiagnostic.MissingVariableDefinition(token.key, token.range)
            return "{{${token.key}}}"
        }

        val currentValue = userValues[token.key] ?: variable.defaultValue.orEmpty()
        if (currentValue.isBlank()) {
            if (variable.required) {
                diagnostics += TemplateDiagnostic.MissingRequiredValue(variable.key)
                return "{{${token.key}}}"
            }
            return ""
        }

        if (variable.type != PromptVariableType.ENUM) return currentValue

        val option = variable.options.firstOrNull { it.id == currentValue }
        if (option == null) {
            diagnostics += TemplateDiagnostic.InvalidEnumValue(variable.key, currentValue)
            return "{{${token.key}}}"
        }
        return option.label
    }

    private fun resolveContext(
        token: PlaceholderToken,
        contextValues: Map<String, ContextValue>,
        diagnostics: MutableList<TemplateDiagnostic>,
    ): String {
        val context = contextValues[token.key]
        if (context == null || context.status == ContextStatus.UNKNOWN) {
            diagnostics += TemplateDiagnostic.UnknownContextVariable(token.key, token.range)
            return "{{${token.key}}}"
        }
        if (context.status != ContextStatus.AVAILABLE || context.value == null) {
            diagnostics += TemplateDiagnostic.ContextUnavailable(
                token.key,
                context.errorMessage ?: "Context '$token.key' is unavailable.",
            )
            return "{{${token.key}}}"
        }
        return context.value
    }

    private data class Replacement(
        val range: SourceRange,
        val value: String,
        val key: String?,
    )
}
