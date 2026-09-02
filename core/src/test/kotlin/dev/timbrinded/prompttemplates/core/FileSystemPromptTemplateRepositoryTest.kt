package dev.timbrinded.prompttemplates.core

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileSystemPromptTemplateRepositoryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `creates loads updates exports and deletes a template`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val id = TemplateId.random()
        val created = assertIs<RepositoryResult.Success<StoredTemplate>>(
            repository.create(
                PromptTemplateDraft(
                    id = id,
                    name = "Review implementation",
                    variables = listOf(PromptVariable("goal", "Goal")),
                    markdown = "Review {{goal}}",
                ),
            ),
        ).value

        assertTrue(Files.isRegularFile(created.directory.resolve("prompt.md")))
        assertTrue(Files.isRegularFile(created.directory.resolve("prompt.meta.json")))
        assertEquals(id, assertIs<RepositoryResult.Success<StoredTemplate>>(repository.load(created.directory)).value.template.id)

        val updated = assertIs<RepositoryResult.Success<StoredTemplate>>(
            repository.update(
                created.directory,
                PromptTemplateDraft(id, "Review implementation", markdown = "Updated"),
            ),
        ).value
        assertEquals("Updated", updated.template.markdown)

        val destination = temporaryDirectory.resolve("export/review.md")
        assertIs<RepositoryResult.Success<Path>>(repository.exportTemplateMarkdown(created.directory, destination))
        assertEquals("Updated", destination.readText())

        assertIs<RepositoryResult.Success<Unit>>(repository.deleteTemplate(created.directory))
        assertTrue(Files.notExists(created.directory))
    }

    @Test
    fun `surfaces missing metadata as recoverable and infers variables`() {
        val root = temporaryDirectory.resolve("library")
        val directory = root.resolve("manual")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("prompt.md"), "# Manual\n\nHello {{name}} {{ide.selection}}")
        val repository = FileSystemPromptTemplateRepository(root)

        val summary = assertIs<LibraryEntry.Template>(repository.scan().children.single()).summary
        assertEquals(TemplateHealth.RECOVERABLE, summary.health)

        val loaded = assertIs<RepositoryResult.Success<StoredTemplate>>(repository.load(directory)).value
        assertTrue(loaded.recoverable)
        assertEquals(listOf("name"), loaded.template.metadata.variables.map(PromptVariable::key))
    }

    @Test
    fun `imports markdown by copying it into the library`() {
        val source = temporaryDirectory.resolve("source.md")
        Files.writeString(source, "# Imported prompt\n\nDo {{task}}")
        val repository = FileSystemPromptTemplateRepository(temporaryDirectory.resolve("library"))

        val imported = assertIs<RepositoryResult.Success<StoredTemplate>>(repository.importMarkdown(source)).value

        assertEquals("Imported prompt", imported.template.metadata.name)
        assertEquals("task", imported.template.metadata.variables.single().key)
        assertTrue(imported.directory.startsWith(repository.root))
        assertTrue(imported.directory.resolve("prompt.md") != source)
    }

    @Test
    fun `creates a new template without changing an existing template`() {
        val repository = FileSystemPromptTemplateRepository(temporaryDirectory.resolve("library"))
        val existing = assertIs<RepositoryResult.Success<StoredTemplate>>(
            repository.create(PromptTemplateDraft(name = "Existing", markdown = "Keep this")),
        ).value

        val created = assertIs<RepositoryResult.Success<StoredTemplate>>(
            repository.create(PromptTemplateDraft(name = "New prompt", markdown = "Save this")),
        ).value

        assertEquals("Save this", created.template.markdown)
        assertEquals(
            "Keep this",
            assertIs<RepositoryResult.Success<StoredTemplate>>(repository.load(existing.directory)).value.template.markdown,
        )
    }

    @Test
    fun `rejects a duplicate prompt name without changing the existing template`() {
        val repository = FileSystemPromptTemplateRepository(temporaryDirectory.resolve("library"))
        val existing = assertIs<RepositoryResult.Success<StoredTemplate>>(
            repository.create(PromptTemplateDraft(name = "Review PR", markdown = "Original")),
        ).value

        val duplicate = repository.create(PromptTemplateDraft(name = "  review pr  ", markdown = "Replacement"))

        val failure = assertIs<RepositoryResult.Failure>(duplicate)
        assertTrue(failure.message.contains("already exists"))
        assertEquals(
            "Original",
            assertIs<RepositoryResult.Success<StoredTemplate>>(repository.load(existing.directory)).value.template.markdown,
        )
        assertEquals(1, repository.scan().children.size)
    }

    @Test
    fun `rejects renaming a template to another prompt name`() {
        val repository = FileSystemPromptTemplateRepository(temporaryDirectory.resolve("library"))
        assertIs<RepositoryResult.Success<StoredTemplate>>(
            repository.create(PromptTemplateDraft(name = "First prompt", markdown = "First")),
        )
        val second = assertIs<RepositoryResult.Success<StoredTemplate>>(
            repository.create(PromptTemplateDraft(name = "Second prompt", markdown = "Second")),
        ).value

        val duplicate = repository.update(
            second.directory,
            PromptTemplateDraft(id = second.template.id, name = "FIRST PROMPT", markdown = "Changed"),
        )

        val failure = assertIs<RepositoryResult.Failure>(duplicate)
        assertTrue(failure.message.contains("already exists"))
        assertEquals(
            "Second",
            assertIs<RepositoryResult.Success<StoredTemplate>>(repository.load(second.directory)).value.template.markdown,
        )
    }

    @Test
    fun `rejects updating a stored template with a different draft id`() {
        val repository = FileSystemPromptTemplateRepository(temporaryDirectory.resolve("library"))
        val existing = assertIs<RepositoryResult.Success<StoredTemplate>>(
            repository.create(PromptTemplateDraft(name = "Existing", markdown = "Original")),
        ).value

        val mismatched = repository.update(
            existing.directory,
            PromptTemplateDraft(name = "New prompt", markdown = "Replacement"),
        )

        val failure = assertIs<RepositoryResult.Failure>(mismatched)
        assertTrue(failure.message.contains("different template"))
        assertEquals(
            "Original",
            assertIs<RepositoryResult.Success<StoredTemplate>>(repository.load(existing.directory)).value.template.markdown,
        )
    }
}
