package dev.timbrinded.prompttemplates.core

class VariableEditorState(
    initialVariables: List<PromptVariable>,
    private val reconciler: TemplateReconciler = TemplateReconciler(),
) {
    private val transientKeys = mutableSetOf<String>()

    var variables: List<PromptVariable> = initialVariables.toList()
        private set

    fun reconcile(markdown: String): ReconciliationResult {
        val intentionalVariables = variables.filterNot { it.key in transientKeys }
        val intentionalKeys = intentionalVariables.mapTo(mutableSetOf(), PromptVariable::key)
        val result = reconciler.reconcile(markdown, intentionalVariables)

        variables = result.variables
        transientKeys.clear()
        result.variables
            .map(PromptVariable::key)
            .filterNot(intentionalKeys::contains)
            .forEach(transientKeys::add)

        return result
    }

    fun updateAt(index: Int, transform: (PromptVariable) -> PromptVariable) {
        if (index !in variables.indices) return
        transientKeys.remove(variables[index].key)
        variables = variables.toMutableList().also { items ->
            items[index] = transform(items[index])
        }
    }

    fun changeTypeAt(index: Int, type: PromptVariableType) {
        updateAt(index) { variable ->
            when (type) {
                PromptVariableType.TEXT, PromptVariableType.MULTILINE -> variable.copy(
                    type = type,
                    defaultValue = variable.defaultValue.takeUnless { variable.type == PromptVariableType.ENUM },
                    options = emptyList(),
                )

                PromptVariableType.ENUM -> {
                    val options = variable.options.ifEmpty {
                        listOf(EnumOption("option", "Option", "Option"))
                    }
                    variable.copy(
                        type = type,
                        required = true,
                        defaultValue = variable.defaultValue
                            ?.takeIf { default -> options.any { it.id == default } }
                            ?: options.first().id,
                        options = options,
                    )
                }
            }
        }
    }

    fun remove(keys: Set<String>) {
        variables = variables.filterNot { it.key in keys }
        transientKeys.removeAll(keys)
    }

    /** Restore one command's definition, including a transient counterpart discovered during document undo. */
    fun replaceDefinition(previousKey: String, replacement: PromptVariable?, index: Int) {
        remove(setOfNotNull(previousKey, replacement?.key))
        if (replacement != null) {
            variables = variables.toMutableList().apply { add(index.coerceIn(0, size), replacement) }
        }
    }

    fun move(index: Int, offset: Int) {
        val destination = index + offset
        if (index !in variables.indices || destination !in variables.indices) return
        variables = variables.toMutableList().apply { add(destination, removeAt(index)) }
        // An explicit order makes the current schema intentional, including newly discovered fields.
        transientKeys.clear()
    }
}
