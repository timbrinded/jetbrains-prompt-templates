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
    fun `library refresh does not replace an open new-template draft with the prior selection`() {
        val previouslySelected = LibrarySelectionKey.TemplatePath("existing-prompt")

        val selected = selectLibrarySelectionAfterReload(
            authorOpen = true,
            currentSelection = previouslySelected,
            activeSelection = null,
            persistedTemplateId = null,
        )

        assertNull(selected)
    }

    @Test
    fun `library refresh keeps a newly selected tree entry ahead of stale active detail`() {
        val selected = LibrarySelectionKey.TemplatePath("reviews/new-selection")
        val staleActive = LibrarySelectionKey.TemplatePath("reviews/old-selection")

        assertEquals(
            selected,
            selectLibrarySelectionAfterReload(
                authorOpen = false,
                currentSelection = selected,
                activeSelection = staleActive,
                persistedTemplateId = "persisted-id",
            ),
        )
    }

    @Test
    fun `active detail selection is ignored after the configured library root changes`() {
        val oldRoot = Path.of("workspace", "old-library").toAbsolutePath()
        val newRoot = Path.of("workspace", "new-library").toAbsolutePath()

        assertNull(
            activeTemplateSelection(
                root = newRoot,
                activeDirectory = oldRoot.resolve("template"),
                templateId = "template-id",
            ),
        )
        assertEquals(
            "nested/template",
            activeTemplateSelection(
                root = newRoot,
                activeDirectory = newRoot.resolve("nested/template"),
                templateId = "template-id",
            )?.relativePath,
        )
        assertEquals(
            LibrarySelectionKey.TemplatePath("legacy"),
            activeTemplateSelection(
                root = newRoot,
                activeDirectory = newRoot.resolve("legacy"),
                templateId = null,
            ),
        )
    }

    @Test
    fun `library root change preserves the draft but rebases an existing edit as a new template`() {
        val oldRoot = temporaryDirectory.resolve("old-library")
        val newRoot = temporaryDirectory.resolve("new-library")
        val existing = StoredTemplate(
            PromptTemplateDraft(name = "Draft", markdown = "version one").toTemplate(),
            oldRoot.resolve("reviews/draft"),
        )
        val target = TemplateDetailTarget(existing.directory, existing.template.id.value)
        val author = TemplateAuthorState(
            draft = PromptTemplateDraft(name = "Draft", markdown = "version one"),
            existing = existing,
            existingTarget = target,
            selectionBefore = LibrarySelectionKey.Template(existing.template.id.value, "reviews/draft"),
            destination = oldRoot.resolve("reviews"),
        )

        val rebased = author.rebasedAsNewTemplate(newRoot)

        assertNull(rebased.existing)
        assertNull(rebased.existingTarget)
        assertNull(rebased.selectionBefore)
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
    fun `preferred selection tracks pending until acknowledged`() {
        val tracker = PreferredLibrarySelectionTracker()
        val created = LibrarySelectionKey.Folder("Reviews/New folder")
        val stale = LibrarySelectionKey.Folder("Reviews/Old folder")
        tracker.remember(created)
        assertEquals(created, tracker.preferredOr(stale))
        tracker.acknowledge(stale)
        assertEquals(created, tracker.pendingSelection())
        assertEquals(created, tracker.preferredOr(stale))
        tracker.acknowledge(created)
        assertNull(tracker.pendingSelection())

        // Case-insensitive UUID match on a moved template also acknowledges.
        val templateId = "92ee5ce7-2448-4875-89a7-bd574eacc9e1"
        tracker.remember(LibrarySelectionKey.Template(templateId, "Reviews/original"))
        tracker.acknowledge(LibrarySelectionKey.Template(templateId.uppercase(), "Archive/original"))
        assertNull(tracker.pendingSelection())
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
    fun `reload and scan restart detail only when still selected`() {
        val active = Path.of("library", "reviews", "audit")
        assertTrue(
            shouldReloadSelectedDetail(
                reloadRequested = true,
                authorOpen = false,
                selectedDirectory = active,
                activeDirectory = active,
            ),
        )
        assertFalse(
            shouldReloadSelectedDetail(
                reloadRequested = false,
                authorOpen = false,
                selectedDirectory = active,
                activeDirectory = active,
            ),
        )
        assertFalse(
            shouldReloadSelectedDetail(
                reloadRequested = true,
                authorOpen = true,
                selectedDirectory = active,
                activeDirectory = active,
            ),
        )
        assertTrue(
            shouldReloadHiddenActiveDetail(
                reloadRequested = true,
                authorOpen = false,
                selectedDirectory = null,
                activeDirectory = active,
            ),
        )

        val pending = Path.of("library", "Reviews", "audit")
        assertTrue(
            shouldRestartPendingDetailAfterReload(
                resolvedPendingDirectory = pending,
                selectedTemplateDirectory = pending,
                authorOpen = false,
            ),
        )
        assertFalse(
            shouldRestartPendingDetailAfterReload(
                resolvedPendingDirectory = pending,
                selectedTemplateDirectory = null,
                authorOpen = false,
            ),
        )
        assertFalse(
            shouldRestartPendingDetailAfterReload(
                resolvedPendingDirectory = pending,
                selectedTemplateDirectory = Path.of("library", "Other", "prompt"),
                authorOpen = false,
            ),
        )
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
