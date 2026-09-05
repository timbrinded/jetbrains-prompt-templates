package dev.timbrinded.prompttemplates.core

/** Undo one extraction or rename without restoring unrelated inspector settings from an old snapshot. */
class VariableDefinitionChange(
    private val state: VariableEditorState,
    private val index: Int,
    private val previousKey: String?,
    definition: PromptVariable,
) {
    private var definition = definition

    fun undo() {
        definition = state.variables.firstOrNull { it.key == definition.key } ?: definition
        val previous = previousKey?.let { definition.copy(key = it) }
        state.replaceDefinition(definition.key, previous, index)
    }

    fun redo() {
        if (previousKey != null) {
            state.variables.firstOrNull { it.key == previousKey }?.let { definition = it.copy(key = definition.key) }
        }
        state.replaceDefinition(previousKey ?: definition.key, definition, index)
    }
}
