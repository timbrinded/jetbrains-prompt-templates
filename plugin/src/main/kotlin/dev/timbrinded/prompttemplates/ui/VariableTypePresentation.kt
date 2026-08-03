package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.PromptVariableType

internal data class VariableTypePresentation(
    val requiredVisible: Boolean,
    val enumChoicesVisible: Boolean,
)

internal fun variableTypePresentation(type: PromptVariableType?): VariableTypePresentation =
    VariableTypePresentation(
        requiredVisible = type != null && type != PromptVariableType.ENUM,
        enumChoicesVisible = type == PromptVariableType.ENUM,
    )
