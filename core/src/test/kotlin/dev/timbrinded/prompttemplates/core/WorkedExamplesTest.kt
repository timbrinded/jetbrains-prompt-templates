package dev.timbrinded.prompttemplates.core

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class WorkedExamplesTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `every shipped package passes codec parser and its documented renderer fixture`() {
        val codec = TemplateMetadataCodec()
        val examples = WorkedExamples.all
        assertEquals(3, examples.size)
        assertEquals(examples.size, examples.map { it.template.id }.distinct().size)
        assertEquals(PromptVariableType.entries.toSet(), examples.flatMap { it.template.metadata.variables }.map { it.type }.toSet())
        for (example in examples) {
            assertEquals(example.template.metadata,
                assertIs<MetadataDecodeResult.Success>(codec.decode(codec.encode(example.template.metadata))).metadata)
            assertEquals(emptyList(), LinearPlaceholderParser().parse(example.template.markdown).diagnostics)
            val rendered = StrictPromptRenderer().render(example.template, example.sampleValues,
                example.sampleContext.mapValues { ContextValue.available(it.value) })
            assertEquals(emptyList(), rendered.diagnostics, example.key)
            assertEquals(example.expectedOutput, rendered.renderedText, example.key)
        }
    }

    @Test
    fun `missing real input or selection blocks output instead of using walkthrough data`() {
        val renderer = StrictPromptRenderer()
        for (example in WorkedExamples.all) {
            val unavailable = example.sampleContext.mapValues { ContextValue.unavailable("Select text in an editor, then Refresh Context.") }
            val result = renderer.render(example.newDraft().toTemplate(), emptyMap(), unavailable)
            assertFalse(result.isValid, example.key)
            assertTrue(result.diagnostics.any { it is TemplateDiagnostic.ContextUnavailable || it is TemplateDiagnostic.MissingRequiredValue })
            assertNotEquals(example.expectedOutput, result.renderedText)
        }
    }

    @Test
    fun `copies have independent identity preserve edits and export only Markdown`() {
        val repository = FileSystemPromptTemplateRepository(temporary.resolve("library"))
        for (example in WorkedExamples.all) {
            val first = assertIs<RepositoryResult.Success<StoredTemplate>>(repository.create(example.newDraft())).value
            assertNotEquals(example.template.id, first.template.id)
            val exported = temporary.resolve("${example.key}.md")
            assertIs<RepositoryResult.Success<Path>>(repository.exportTemplateMarkdown(first.directory, exported))
            assertEquals(example.template.markdown, exported.readText())
            val edited = example.newDraft().copy(id = first.template.id, markdown = "User-edited copy\n")
            assertIs<RepositoryResult.Success<StoredTemplate>>(repository.update(first.directory, edited, first.revision))
            val copyDraft = example.newDraft("${example.template.metadata.name} copy")
            val second = assertIs<RepositoryResult.Success<StoredTemplate>>(repository.create(copyDraft)).value
            assertNotEquals(first.template.id, second.template.id)
            assertEquals(example.template.markdown, second.template.markdown)
            assertIs<RepositoryResult.Failure>(repository.create(copyDraft.copy(name = "UUID collision")))
            assertIs<RepositoryResult.Failure>(repository.create(example.newDraft()))
            assertEquals("User-edited copy\n", first.directory.resolve("prompt.md").readText())
            assertEquals(example.template.markdown, second.directory.resolve("prompt.md").readText())
        }
    }
}
