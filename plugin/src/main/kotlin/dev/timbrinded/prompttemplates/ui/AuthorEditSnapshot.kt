package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.PromptTemplateDraft
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType

/** Raw editable text matters too: an unapplied rename or enum input must survive Keep Editing. */
internal data class AuthorEditSnapshot(
    val draft: PromptTemplateDraft,
    val tagsText: String,
    val pendingKey: String?,
    val pendingEnumText: String?,
) {
    companion object {
        fun capture(
            draft: PromptTemplateDraft,
            tagsText: String,
            selected: PromptVariable?,
            keyText: String,
            enumText: String,
        ) = AuthorEditSnapshot(
            draft = draft,
            tagsText = tagsText,
            pendingKey = keyText.takeIf { selected != null && it != selected.key },
            pendingEnumText = enumText.takeIf {
                selected?.type == PromptVariableType.ENUM && it != selected.options.joinToString("; ") { option -> option.label }
            },
        )
    }
}
