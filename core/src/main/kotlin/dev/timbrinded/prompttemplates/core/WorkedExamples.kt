package dev.timbrinded.prompttemplates.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class WorkedExample(
    val key: String,
    val template: PromptTemplate,
    val sampleValues: Map<String, String>,
    val sampleContext: Map<String, String>,
    val expectedOutput: String,
) {
    fun newDraft(name: String = template.metadata.name): PromptTemplateDraft = PromptTemplateDraft(
        name = name,
        description = template.metadata.description,
        tags = template.metadata.tags,
        variables = template.metadata.variables,
        markdown = template.markdown,
    )

    override fun toString(): String = template.metadata.name
}

/** Bundled walkthrough data is separate from the ordinary two-file template packages. */
object WorkedExamples {
    val all: List<WorkedExample> by lazy {
        val codec = TemplateMetadataCodec()
        Json.decodeFromString<List<ExampleFixture>>(read("fixtures.json")).map { fixture ->
            val decoded = codec.decode(read("${fixture.key}/prompt.meta.json"))
            require(decoded is MetadataDecodeResult.Success) { "Invalid bundled example: ${fixture.key}" }
            WorkedExample(fixture.key, PromptTemplate(decoded.metadata, read("${fixture.key}/prompt.md")),
                fixture.values, fixture.context, read("expected/${fixture.key}.md"))
        }
    }

    private fun read(path: String): String = requireNotNull(
        WorkedExamples::class.java.getResourceAsStream("/dev/timbrinded/prompttemplates/examples/$path"),
    ) { "Missing bundled example resource: $path" }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

@Serializable
private data class ExampleFixture(
    val key: String,
    val values: Map<String, String> = emptyMap(),
    val context: Map<String, String> = emptyMap(),
)
