package dev.timbrinded.prompttemplates.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

sealed interface MetadataDecodeResult {
    data class Success(val metadata: TemplateMetadata) : MetadataDecodeResult
    data class Invalid(val message: String, val cause: Throwable? = null) : MetadataDecodeResult
    data class UnsupportedVersion(val found: Int) : MetadataDecodeResult
}

@OptIn(ExperimentalSerializationApi::class)
class TemplateMetadataCodec(
    private val json: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    },
) {
    fun encode(metadata: TemplateMetadata): String =
        json.encodeToString(TemplateMetadata.serializer(), metadata.withLiteralEnumChoices()) + "\n"

    fun decode(raw: String): MetadataDecodeResult {
        val metadata = try {
            json.decodeFromString(TemplateMetadata.serializer(), raw)
        } catch (error: SerializationException) {
            return MetadataDecodeResult.Invalid("Metadata is not valid schema JSON.", error)
        } catch (error: IllegalArgumentException) {
            return MetadataDecodeResult.Invalid("Metadata contains an invalid value.", error)
        }

        if (metadata.schemaVersion > CURRENT_SCHEMA_VERSION) {
            return MetadataDecodeResult.UnsupportedVersion(metadata.schemaVersion)
        }

        val literalMetadata = metadata.withLiteralEnumChoices()
        val error = validate(literalMetadata)
        return if (error == null) {
            MetadataDecodeResult.Success(literalMetadata)
        } else {
            MetadataDecodeResult.Invalid(error)
        }
    }

    fun validate(metadata: TemplateMetadata): String? {
        if (metadata.schemaVersion != CURRENT_SCHEMA_VERSION) {
            return "Unsupported metadata schema version ${metadata.schemaVersion}."
        }
        if (!TemplateId.isValid(metadata.id)) {
            return "Template id must be a UUID."
        }
        if (metadata.name.isBlank()) return "Template name is required."

        val duplicate = metadata.variables.groupingBy(PromptVariable::key)
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
        if (duplicate != null) return "Variable '${duplicate.key}' is defined more than once."

        metadata.variables.forEach { variable ->
            if (!USER_VARIABLE_KEY_REGEX.matches(variable.key)) {
                return "Invalid user variable key '${variable.key}'."
            }
            if (variable.label.isBlank()) return "Variable '${variable.key}' needs a label."
            if (variable.minimumRows != null && variable.minimumRows < 1) {
                return "Variable '${variable.key}' needs a positive minimum row count."
            }
            if (variable.type == PromptVariableType.ENUM) {
                if (!variable.required) return "Enum '${variable.key}' always requires a choice."
                if (variable.options.isEmpty()) return "Enum '${variable.key}' needs at least one option."
                if (variable.options.any { it.id.isBlank() || it.label.isBlank() }) {
                    return "Enum '${variable.key}' contains an invalid option."
                }
                if (variable.options.any { it.label != it.value }) {
                    return "Enum '${variable.key}' must contain literal choices."
                }
                if (variable.options.map(EnumOption::id).distinct().size != variable.options.size) {
                    return "Enum '${variable.key}' contains duplicate option ids."
                }
                if (variable.defaultValue != null && variable.options.none { it.id == variable.defaultValue }) {
                    return "Enum '${variable.key}' has an unknown default option."
                }
            }
        }
        return null
    }
}
