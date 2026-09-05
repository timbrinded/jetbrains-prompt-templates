package dev.timbrinded.prompttemplates.core

data class ContextVariableDescription(val key: String, val explanation: String)

val BUILT_IN_CONTEXT_VARIABLES: List<ContextVariableDescription> = listOf(
    ContextVariableDescription("ide.selection", "Selected text captured from the source editor."),
    ContextVariableDescription("ide.file.name", "The source file name, including its extension."),
    ContextVariableDescription("ide.file.path", "The full path of the source file."),
    ContextVariableDescription("ide.file.relativePath", "The source file path relative to the project."),
    ContextVariableDescription("ide.language", "The source file type description reported by the IDE."),
    ContextVariableDescription("ide.project.name", "The current project name."),
    ContextVariableDescription("clipboard", "Plain text captured from the clipboard."),
)

val BUILT_IN_CONTEXT_KEYS: Set<String> = BUILT_IN_CONTEXT_VARIABLES.mapTo(linkedSetOf()) { it.key }

fun userVariableKeyError(key: String, existingKeys: Collection<String>): String? = when {
    key in BUILT_IN_CONTEXT_KEYS || key.startsWith("ide.") -> "'$key' is reserved for IDE context."
    !USER_VARIABLE_KEY_REGEX.matches(key) -> "Use a letter or underscore first, then letters, digits, underscores or hyphens."
    key in existingKeys -> "Variable '$key' already exists."
    else -> null
}

/** Reject positions that would escape the inserted token or embed it inside another placeholder. */
fun canInsertPlaceholder(markdown: String, range: SourceRange, key: String): Boolean {
    if (range.endExclusive > markdown.length || !PLACEHOLDER_KEY_REGEX.matches(key)) return false
    val token = "{{$key}}"
    val updated = markdown.replaceRange(range.start, range.endExclusive, token)
    return LinearPlaceholderParser().parse(updated).placeholders.any {
        it.key == key && it.range == SourceRange(range.start, range.start + token.length)
    }
}
