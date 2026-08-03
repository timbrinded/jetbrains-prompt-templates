package dev.timbrinded.prompttemplates.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@JvmInline
value class TemplateId(val value: String) {
    init {
        require(runCatching { UUID.fromString(value) }.isSuccess) { "Template ID must be a UUID" }
    }

    companion object {
        fun random(): TemplateId = TemplateId(UUID.randomUUID().toString())
    }
}

@Serializable
enum class PromptVariableType {
    @SerialName("text")
    TEXT,

    @SerialName("multiline")
    MULTILINE,

    @SerialName("enum")
    ENUM,
}

@Serializable
data class EnumOption(
    val id: String,
    val label: String,
    val value: String,
)

@Serializable
data class PromptVariable(
    val key: String,
    val label: String,
    val type: PromptVariableType = PromptVariableType.TEXT,
    val required: Boolean = true,
    val description: String? = null,
    val defaultValue: String? = null,
    val placeholder: String? = null,
    val minimumRows: Int? = null,
    val options: List<EnumOption> = emptyList(),
)

@Serializable
data class TemplateMetadata(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val variables: List<PromptVariable> = emptyList(),
)

data class PromptTemplate(
    val metadata: TemplateMetadata,
    val markdown: String,
) {
    val id: TemplateId get() = TemplateId(metadata.id)
}

data class PromptTemplateDraft(
    val id: TemplateId = TemplateId.random(),
    val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val variables: List<PromptVariable> = emptyList(),
    val markdown: String,
) {
    fun toTemplate(): PromptTemplate = PromptTemplate(
        metadata = TemplateMetadata(
            id = id.value,
            name = name.trim(),
            description = description?.trim()?.ifEmpty { null },
            tags = tags.map(String::trim).filter(String::isNotEmpty).distinct(),
            variables = variables,
        ),
        markdown = markdown,
    )
}

data class SourceRange(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0 && endExclusive >= start)
    }
}

enum class DiagnosticSeverity { WARNING, ERROR }

sealed interface TemplateDiagnostic {
    val message: String
    val severity: DiagnosticSeverity

    data class SyntaxError(
        val range: SourceRange,
        override val message: String,
        override val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    ) : TemplateDiagnostic

    data class MissingRequiredValue(
        val key: String,
        override val message: String = "A value is required for '$key'.",
        override val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    ) : TemplateDiagnostic

    data class MissingVariableDefinition(
        val key: String,
        val range: SourceRange,
        override val message: String = "No variable definition exists for '$key'.",
        override val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    ) : TemplateDiagnostic

    data class UnknownContextVariable(
        val key: String,
        val range: SourceRange,
        override val message: String = "No context provider is registered for '$key'.",
        override val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    ) : TemplateDiagnostic

    data class ContextUnavailable(
        val key: String,
        override val message: String,
        override val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    ) : TemplateDiagnostic

    data class InvalidEnumValue(
        val key: String,
        val value: String,
        override val message: String = "'$value' is not a valid option for '$key'.",
        override val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    ) : TemplateDiagnostic

    data class UnusedVariableDefinition(
        val key: String,
        override val message: String = "'$key' is defined but not used in the template.",
        override val severity: DiagnosticSeverity = DiagnosticSeverity.WARNING,
    ) : TemplateDiagnostic
}

enum class ContextStatus { AVAILABLE, UNAVAILABLE, UNKNOWN }

data class ContextValue(
    val status: ContextStatus,
    val value: String? = null,
    val displaySummary: String? = null,
    val errorMessage: String? = null,
) {
    companion object {
        fun available(value: String, summary: String? = null) =
            ContextValue(ContextStatus.AVAILABLE, value, summary)

        fun unavailable(message: String) =
            ContextValue(ContextStatus.UNAVAILABLE, errorMessage = message)
    }
}

const val CURRENT_SCHEMA_VERSION = 1

val USER_VARIABLE_KEY_REGEX = Regex("[A-Za-z_][A-Za-z0-9_-]*")
val PLACEHOLDER_KEY_REGEX = Regex(
    "[A-Za-z_][A-Za-z0-9_-]*(?:\\.[A-Za-z_][A-Za-z0-9_-]*)*",
)

fun defaultVariableLabel(key: String): String = key
    .replace('-', ' ')
    .replace('_', ' ')
    .split(' ')
    .filter(String::isNotBlank)
    .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }
