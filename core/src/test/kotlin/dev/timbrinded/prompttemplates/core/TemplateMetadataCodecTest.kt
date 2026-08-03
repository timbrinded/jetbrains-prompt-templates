package dev.timbrinded.prompttemplates.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TemplateMetadataCodecTest {
    private val codec = TemplateMetadataCodec()

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
                    options = listOf(EnumOption("deep", "Deep", "Deep review")),
                ),
            ),
        )

        assertTrue(codec.validate(invalid)!!.contains("unknown default"))
    }
}
