package dev.timbrinded.prompttemplates.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateMetadataCodecTest {
    private val codec = TemplateMetadataCodec()

    @Test
    fun `default absence empty values presentation settings and order round trip without a new schema`() {
        val metadata = TemplateMetadata(id = TemplateId.random().value, name = "Defaults", variables = listOf(
            PromptVariable("none", "None"),
            PromptVariable("empty", "Empty", defaultValue = "", placeholder = "Enter text"),
            PromptVariable("notes", "Notes", type = PromptVariableType.MULTILINE,
                defaultValue = "First\nSecond", placeholder = "Enter notes", minimumRows = 7),
            PromptVariable("mode", "Mode", type = PromptVariableType.ENUM, defaultValue = "long-id",
                options = listOf(EnumOption("short-id", "Short", "Short"), EnumOption("long-id", "Long", "Long"))),
        ))
        assertEquals(metadata, assertIs<MetadataDecodeResult.Success>(codec.decode(codec.encode(metadata))).metadata)
        val required = PromptTemplate(metadata, "{{none}} {{empty}}")
        val diagnostics = StrictPromptRenderer().render(required, emptyMap(), emptyMap()).diagnostics
        assertEquals(setOf("none", "empty"), diagnostics.filterIsInstance<TemplateDiagnostic.MissingRequiredValue>().map { it.key }.toSet())
        assertNotNull(codec.validate(metadata.copy(variables = listOf(metadata.variables[2].copy(minimumRows = 0)))))
    }

    @Test
    fun `round trips deterministically and tolerates unknown fields`() {
        val metadata = TemplateMetadata(
            id = TemplateId.random().value,
            name = "Review implementation",
            tags = listOf("review", "code"),
            variables = listOf(PromptVariable("objective", "Objective", PromptVariableType.MULTILINE)),
        )

        val encoded = codec.encode(metadata)
        val decoded = assertIs<MetadataDecodeResult.Success>(codec.decode(encoded)).metadata
        assertEquals(metadata, decoded)
        assertEquals(encoded, codec.encode(decoded))

        val withUnknownField = encoded.replaceFirst("{", "{\n  \"futureField\": true,")
        assertIs<MetadataDecodeResult.Success>(codec.decode(withUnknownField))
    }

    @Test
    fun `re-encoding over the original JSON keeps unknown keys but never restores cleared known fields`() {
        val original = """
            {
              "futureTop": {"nested": [1, 2.5, "x"]},
              "schemaVersion": 1,
              "id": "${TemplateId.random().value}",
              "name": "Old name",
              "description": "Cleared by the edit",
              "variables": [
                {"key": "mode", "label": "Mode", "type": "enum", "futureVariable": "kept",
                 "options": [{"id": "a", "label": "A", "value": "A", "futureOption": 3}]},
                {"key": "renamed", "label": "Renamed", "futureVariable": "dropped with its key"}
              ],
              "zFuture": true
            }
        """.trimIndent()
        val decoded = assertIs<MetadataDecodeResult.Success>(codec.decode(original)).metadata
        val edited = decoded.copy(
            name = "New name",
            description = null,
            variables = listOf(decoded.variables[0], decoded.variables[1].copy(key = "other")),
        )

        val encoded = codec.encode(edited, original)

        assertEquals(edited, assertIs<MetadataDecodeResult.Success>(codec.decode(encoded)).metadata)
        val json = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(listOf("schemaVersion", "id", "name", "tags", "variables", "futureTop", "zFuture"), json.keys.toList())
        assertEquals(Json.parseToJsonElement(original).jsonObject["futureTop"], json["futureTop"])
        val variables = json.getValue("variables").jsonArray.map { it.jsonObject }
        assertEquals(JsonPrimitive("kept"), variables[0]["futureVariable"])
        assertEquals(JsonPrimitive(3), variables[0].getValue("options").jsonArray.single().jsonObject["futureOption"])
        assertNull(variables[1]["futureVariable"])
        assertEquals(codec.encode(edited), codec.encode(edited, codec.encode(edited)))
    }

    @Test
    fun `rejects unsupported future schema without migration`() {
        val raw = codec.encode(TemplateMetadata(id = TemplateId.random().value, name = "Future"))
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")

        val result = assertIs<MetadataDecodeResult.UnsupportedVersion>(codec.decode(raw))
        assertEquals(99, result.found)
    }

    @Test
    fun `validates enum defaults`() {
        val invalid = TemplateMetadata(
            id = TemplateId.random().value,
            name = "Invalid",
            variables = listOf(
                PromptVariable(
                    key = "depth",
                    label = "Depth",
                    type = PromptVariableType.ENUM,
                    defaultValue = "missing",
                    options = listOf(EnumOption("deep", "Deep", "Deep")),
                ),
            ),
        )

        assertContains(assertNotNull(codec.validate(invalid)), "unknown default")
    }

    @Test
    fun `rejects malformed template ids`() {
        val metadata = TemplateMetadata(id = "not-a-uuid", name = "Invalid")

        assertEquals("Template id must be a UUID.", codec.validate(metadata))
    }

    @Test
    fun `expands saved label to output mappings into literal choices`() {
        val raw = """
            {
              "schemaVersion": 1,
              "id": "${TemplateId.random().value}",
              "name": "PR Explainer",
              "variables": [
                {
                  "key": "font-icon",
                  "label": "Font Icon",
                  "type": "enum",
                  "required": false,
                  "defaultValue": "huge-icons",
                  "options": [
                    {
                      "id": "huge-icons",
                      "label": "huge-icons",
                      "value": "lucide-react-icons"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val metadata = assertIs<MetadataDecodeResult.Success>(codec.decode(raw)).metadata
        val variable = metadata.variables.single()
        val options = variable.options

        assertTrue(variable.required)
        assertEquals(listOf("huge-icons", "lucide-react-icons"), options.map { it.label })
        assertEquals(options.map { it.label }, options.map { it.value })
    }

    @Test
    fun `removes enum choices from text variables`() {
        val metadata = TemplateMetadata(
            id = TemplateId.random().value,
            name = "Text",
            variables = listOf(
                PromptVariable(
                    key = "objective",
                    label = "Objective",
                    defaultValue = "Review it",
                    options = listOf(EnumOption("unused", "Unused", "Unused")),
                ),
            ),
        )

        val decoded = assertIs<MetadataDecodeResult.Success>(codec.decode(codec.encode(metadata))).metadata
        val variable = decoded.variables.single()

        assertEquals("Review it", variable.defaultValue)
        assertEquals(emptyList(), variable.options)
    }

    @Test
    fun `rejects duplicate variable keys`() {
        val metadata = TemplateMetadata(
            id = TemplateId.random().value,
            name = "Duplicate variables",
            variables = listOf(
                PromptVariable("objective", "Objective"),
                PromptVariable("objective", "Other objective"),
            ),
        )

        assertEquals("Variable 'objective' is defined more than once.", codec.validate(metadata))
    }
}
