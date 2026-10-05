package dev.timbrinded.prompttemplates.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
    /**
     * Encodes [metadata]. With the [original] JSON it replaces, keys this version does not know, such as fields
     * written by a newer plugin version, follow the known keys in their original order instead of being dropped.
     * Variables and enum options keep their unknown keys while their key or id is unchanged.
     */
    fun encode(metadata: TemplateMetadata, original: String? = null): String {
        val literal = metadata.withLiteralEnumChoices()
        val previous = original?.let(::parseObject)
            ?: return json.encodeToString(TemplateMetadata.serializer(), literal) + "\n"
        val encoded = json.encodeToJsonElement(TemplateMetadata.serializer(), literal) as JsonObject
        val merged = encoded.withUnknownKeysFrom(previous, TemplateMetadata.serializer().descriptor)
        return json.encodeToString(JsonElement.serializer(), merged) + "\n"
    }

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

    private fun parseObject(raw: String): JsonObject? = try {
        json.parseToJsonElement(raw) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun JsonObject.withUnknownKeysFrom(original: JsonObject, descriptor: SerialDescriptor): JsonObject {
        val known = descriptor.elementNames.toSet()
        val merged = mapValuesTo(LinkedHashMap()) { (name, value) ->
            val identity = IDENTITY_KEYS[name]
            val previousItems = original[name] as? JsonArray
            if (identity == null || value !is JsonArray || previousItems == null) return@mapValuesTo value
            val itemDescriptor = descriptor.getElementDescriptor(descriptor.getElementIndex(name)).getElementDescriptor(0)
            val previousById = previousItems.filterIsInstance<JsonObject>().associateBy { it[identity] }
            JsonArray(value.map { item ->
                val previous = previousById[(item as JsonObject)[identity]]
                previous?.let { item.withUnknownKeysFrom(it, itemDescriptor) } ?: item
            })
        }
        original.forEach { (name, value) -> if (name !in known) merged[name] = value }
        return JsonObject(merged)
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

/** Identity of the objects in each metadata list, used to carry their unknown keys across a save. */
private val IDENTITY_KEYS = mapOf("variables" to "key", "options" to "id")
