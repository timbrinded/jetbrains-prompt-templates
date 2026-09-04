package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.PromptTemplateDraft
import dev.timbrinded.prompttemplates.core.RepositoryResult
import dev.timbrinded.prompttemplates.core.StoredTemplate
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PromptTemplatesPanelTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `library root change preserves the draft but rebases an existing edit as a new template`() {
        val oldRoot = temporaryDirectory.resolve("old-library")
        val newRoot = temporaryDirectory.resolve("new-library")
        val existing = StoredTemplate(
            PromptTemplateDraft(name = "Draft", markdown = "version one").toTemplate(),
            oldRoot.resolve("reviews/draft"),
        )
        val author = TemplateAuthorState(
            draft = PromptTemplateDraft(name = "Draft", markdown = "version one"),
            existing = existing,
            destination = oldRoot.resolve("reviews"),
        )

        val rebased = author.rebasedAsNewTemplate(newRoot)

        assertNull(rebased.existing)
        assertEquals(newRoot.toAbsolutePath().normalize(), rebased.destination)
        assertEquals(rebased, rebased.rebasedAsNewTemplate(newRoot.resolve(".")))
    }

    @Test
    fun `detail loads track generations and invalidate only prior results`() {
        val generations = LoadGenerationTracker()
        val libraryGeneration = generations.beginLibraryLoad()
        val target = TemplateDetailTarget(Path.of("library", "prompt"), "template-id")

        // Detail activity alone does not invalidate the in-flight library scan.
        generations.beginDetailLoad(target, TemplateDetailIntent.USE)
        generations.invalidateDetailLoad()
        assertTrue(generations.isCurrentLibraryLoad(libraryGeneration))

        // A newer detail request invalidates only the prior detail result.
        val firstDetailRequest = generations.beginDetailLoad(target, TemplateDetailIntent.USE)
        val secondDetailRequest = generations.beginDetailLoad(target, TemplateDetailIntent.EDIT)
        assertTrue(generations.isCurrentLibraryLoad(libraryGeneration))
        assertFalse(generations.acceptDetailLoad(firstDetailRequest))
        assertTrue(generations.acceptDetailLoad(secondDetailRequest))
    }

    @Test
    fun `author callbacks reject stale requests and retain destinations`() {
        val tracker = AuthorAsyncRequestTracker()
        val save = requireNotNull(tracker.beginSave(Path.of("library", "original")))
        tracker.invalidate()
        assertFalse(tracker.isCurrent(save))

        val firstDestination = Path.of("library", "First")
        val secondDestination = Path.of("library", "Second")
        val first = tracker.begin(firstDestination)
        val second = tracker.begin(secondDestination)
        assertEquals(firstDestination, first.destination)
        assertEquals(secondDestination, second.destination)
        assertFalse(tracker.isCurrent(first))
        assertTrue(tracker.isCurrent(second))
    }

    @Test
    fun `a second save is rejected while the first save is in progress`() {
        val tracker = AuthorAsyncRequestTracker()
        val nestedDestination = Path.of("library", "Reviews", "Security")

        val first = requireNotNull(tracker.beginSave(nestedDestination))
        val second = tracker.beginSave(Path.of("library"))

        assertEquals(nestedDestination, first.destination)
        assertNull(second)
        tracker.finishSave(first)
        assertEquals(nestedDestination, tracker.beginSave(nestedDestination)?.destination)
    }

    @Test
    fun `search indexing only reads regular template files`() {
        val outside = temporaryDirectory.resolve("outside.md")
        val linkedMarkdown = temporaryDirectory.resolve("prompt.md")
        Files.writeString(outside, "private outside content")
        Files.createSymbolicLink(linkedMarkdown, outside)
        assertEquals("private outside content", readSearchIndexBody(outside))
        assertEquals("", readSearchIndexBody(linkedMarkdown))

        val directoryNamedMarkdown = Files.createDirectory(temporaryDirectory.resolve("special-prompt.md"))
        assertEquals("", readSearchIndexBody(directoryNamedMarkdown))
    }

    @Test
    fun `unexpected repository exception becomes a failure and retains its cause`() {
        val exception = IllegalStateException("broken iterator")

        val result = runRepositoryOperationSafely<Unit> { throw exception }

        val failure = assertIs<RepositoryResult.Failure>(result)
        assertEquals("Unexpected repository error: broken iterator", failure.message)
        assertSame(exception, failure.cause)
    }

    @Test
    fun `repository operation fail-safe does not hide cancellation`() {
        val cancellation = CancellationException("stop")

        val thrown = assertFailsWith<CancellationException> {
            runRepositoryOperationSafely<Unit> { throw cancellation }
        }

        assertSame(cancellation, thrown)
    }

}
