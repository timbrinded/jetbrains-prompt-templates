package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.LibraryEntry
import dev.timbrinded.prompttemplates.core.LibrarySnapshot
import dev.timbrinded.prompttemplates.core.PromptTemplateDraft
import dev.timbrinded.prompttemplates.core.RepositoryResult
import dev.timbrinded.prompttemplates.core.StoredTemplate
import dev.timbrinded.prompttemplates.core.TemplateHealth
import dev.timbrinded.prompttemplates.core.TemplateId
import dev.timbrinded.prompttemplates.core.TemplateSummary
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PromptTemplatesPanelTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `library refresh does not replace an open new-template draft with the prior selection`() {
        val previouslySelected = LibrarySelectionKey(relativePath = "existing-prompt")

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
        val selected = LibrarySelectionKey(relativePath = "reviews/new-selection")
        val staleActive = LibrarySelectionKey(relativePath = "reviews/old-selection")

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
            LibrarySelectionKey(templateId = null, relativePath = "legacy"),
            activeTemplateSelection(
                root = newRoot,
                activeDirectory = newRoot.resolve("legacy"),
                templateId = null,
            ),
        )
    }

    @Test
    fun `library watcher rebinds only when the normalized root changes`() {
        val currentRoot = Path.of("workspace", "library").toAbsolutePath()

        assertTrue(!shouldRebindLibraryWatcher(currentRoot, currentRoot.resolve(".")))
        assertTrue(shouldRebindLibraryWatcher(currentRoot, currentRoot.resolveSibling("other-library")))
        assertTrue(shouldRebindLibraryWatcher(null, currentRoot))
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
        val session = AuthorSessionState(
            existing = existing,
            existingTarget = target,
            selectionBefore = LibrarySelectionKey(existing.template.id.value, "reviews/draft"),
            destination = oldRoot.resolve("reviews"),
        )

        assertTrue(session.rebaseAsNewTemplate(newRoot))

        assertNull(session.existing)
        assertNull(session.existingTarget)
        assertNull(session.selectionBefore)
        assertEquals(newRoot.toAbsolutePath().normalize(), session.destination)
        assertTrue(!session.rebaseAsNewTemplate(newRoot.resolve(".")))
    }

    @Test
    fun `saving a template refreshes and selects its stored directory`() {
        val savedDirectory = Path.of("library", "new-prompt")
        val events = mutableListOf<String>()

        afterTemplateSaved(
            savedDirectory = savedDirectory,
            showSaved = { events += "show" },
            refreshLibrary = { selected -> events += "refresh:$selected" },
        )

        assertEquals(listOf("show", "refresh:$savedDirectory"), events)
    }

    @Test
    fun `deleting a template clears selection and refreshes the library`() {
        val events = mutableListOf<String>()

        afterTemplateDeleted(
            clearSelection = { events += "clear" },
            refreshLibrary = { selected -> events += "refresh:$selected" },
        )

        assertEquals(listOf("clear", "refresh:null"), events)
    }

    @Test
    fun `a failed load discards a summary when its directory is gone`() {
        val result: RepositoryResult<StoredTemplate> = RepositoryResult.Failure("Template directory does not exist.")

        assertTrue(shouldDiscardTemplateSummary(result, directoryMissing = true))
    }

    @Test
    fun `all detail load failures clear missing entries or replace stale detail with an error`() {
        val failure: RepositoryResult<StoredTemplate> = RepositoryResult.Failure("Unable to load selected template.")

        assertEquals(
            DetailLoadFailureAction.CLEAR_AND_RELOAD,
            detailLoadFailureAction(failure, directoryMissing = true),
        )
        assertEquals(
            DetailLoadFailureAction.SHOW_ERROR,
            detailLoadFailureAction(failure, directoryMissing = false),
        )
    }

    @Test
    fun `cancel existing edit resolves the latest snapshot entry and clears a deleted entry`() {
        val root = temporaryDirectory.resolve("library")
        val id = TemplateId.random()
        val directory = root.resolve("reviews/draft")
        val target = TemplateDetailTarget(directory, id.value)
        val latest = LibraryEntry.Template(
            summary = TemplateSummary(
                id = id,
                name = "Version two",
                description = "Changed outside the IDE",
                tags = emptyList(),
                directory = directory,
                health = TemplateHealth.HEALTHY,
            ),
            relativeDirectory = Path.of("reviews/draft"),
        )

        val resolved = resolveEditedTemplateAfterCancel(target, LibrarySnapshot(root, listOf(latest)))

        assertEquals("Version two", resolved?.summary?.name)
        assertNull(resolveEditedTemplateAfterCancel(target, LibrarySnapshot(root, emptyList())))
    }

    @Test
    fun `cancel existing edit follows one unique UUID move`() {
        val root = temporaryDirectory.resolve("library")
        val id = TemplateId.random()
        val oldDirectory = root.resolve("reviews/draft")
        val movedDirectory = root.resolve("archive/draft")
        val moved = LibraryEntry.Template(
            summary = TemplateSummary(
                id = id,
                name = "Moved draft",
                description = null,
                tags = emptyList(),
                directory = movedDirectory,
                health = TemplateHealth.HEALTHY,
            ),
            relativeDirectory = Path.of("archive/draft"),
        )

        assertEquals(
            movedDirectory,
            resolveEditedTemplateAfterCancel(
                TemplateDetailTarget(oldDirectory, id.value),
                LibrarySnapshot(root, listOf(moved)),
            )?.directory,
        )
    }

    @Test
    fun `detail activity does not invalidate an in-flight library scan`() {
        val generations = LoadGenerationTracker()
        val libraryGeneration = generations.beginLibraryLoad()
        val target = TemplateDetailTarget(Path.of("library", "prompt"), "template-id")

        generations.beginDetailLoad(target, TemplateDetailIntent.USE)
        generations.invalidateDetailLoad()

        assertTrue(generations.isCurrentLibraryLoad(libraryGeneration))
    }

    @Test
    fun `a newer detail request invalidates only the prior detail result`() {
        val generations = LoadGenerationTracker()
        val libraryGeneration = generations.beginLibraryLoad()
        val target = TemplateDetailTarget(Path.of("library", "prompt"), "template-id")
        val firstDetailRequest = generations.beginDetailLoad(target, TemplateDetailIntent.USE)

        val secondDetailRequest = generations.beginDetailLoad(target, TemplateDetailIntent.EDIT)

        assertTrue(generations.isCurrentLibraryLoad(libraryGeneration))
        assertTrue(!generations.acceptDetailLoad(firstDetailRequest))
        assertTrue(generations.acceptDetailLoad(secondDetailRequest))
    }

    @Test
    fun `preferred mutation selection survives a superseding watcher reload until applied`() {
        val tracker = PreferredLibrarySelectionTracker()
        val created = LibrarySelectionKey(relativePath = "Reviews/New folder", folder = true)
        val stale = LibrarySelectionKey(relativePath = "Reviews/Old folder", folder = true)

        tracker.remember(created)

        assertEquals(created, tracker.preferredOr(stale))
        tracker.acknowledge(stale)
        assertEquals(created, tracker.pendingSelection())
        assertEquals(created, tracker.preferredOr(stale))
        tracker.acknowledge(created)
        assertNull(tracker.pendingSelection())
    }

    @Test
    fun `preferred template selection is acknowledged after a second unique move`() {
        val tracker = PreferredLibrarySelectionTracker()
        val templateId = "92ee5ce7-2448-4875-89a7-bd574eacc9e1"
        tracker.remember(
            LibrarySelectionKey(
                templateId = templateId,
                relativePath = "Reviews/original",
            ),
        )

        tracker.acknowledge(
            LibrarySelectionKey(
                templateId = templateId.uppercase(),
                relativePath = "Archive/original",
            ),
        )

        assertNull(tracker.pendingSelection())
    }

    @Test
    fun `a newer author draft rejects an older save callback`() {
        val tracker = AuthorAsyncRequestTracker()
        val save = requireNotNull(tracker.beginSave(Path.of("library", "original")))

        tracker.invalidate()

        assertTrue(!tracker.isCurrent(save))
    }

    @Test
    fun `concurrent imports retain their own destinations and only the newest callback is current`() {
        val tracker = AuthorAsyncRequestTracker()
        val firstDestination = Path.of("library", "First")
        val secondDestination = Path.of("library", "Second")

        val first = tracker.begin(firstDestination)
        val second = tracker.begin(secondDestination)

        assertEquals(firstDestination, first.destination)
        assertEquals(secondDestination, second.destination)
        assertTrue(!tracker.isCurrent(first))
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
    fun `watcher reload refreshes an open template only when it is still selected`() {
        val active = Path.of("library", "reviews", "audit")

        assertTrue(
            shouldReloadSelectedDetail(
                reloadRequested = true,
                authorOpen = false,
                selectedDirectory = active,
                activeDirectory = active,
            ),
        )
        assertTrue(
            !shouldReloadSelectedDetail(
                reloadRequested = false,
                authorOpen = false,
                selectedDirectory = active,
                activeDirectory = active,
            ),
        )
        assertTrue(
            !shouldReloadSelectedDetail(
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
    }

    @Test
    fun `a scan restarts a pending detail only when the same template remains selected`() {
        val pending = Path.of("library", "Reviews", "audit")

        assertTrue(
            shouldRestartPendingDetailAfterReload(
                resolvedPendingDirectory = pending,
                selectedTemplateDirectory = pending,
                authorOpen = false,
            ),
        )
        assertTrue(
            !shouldRestartPendingDetailAfterReload(
                resolvedPendingDirectory = pending,
                selectedTemplateDirectory = null,
                authorOpen = false,
            ),
        )
        assertTrue(
            !shouldRestartPendingDetailAfterReload(
                resolvedPendingDirectory = pending,
                selectedTemplateDirectory = Path.of("library", "Other", "prompt"),
                authorOpen = false,
            ),
        )
    }

    @Test
    fun `folder reload reconciles an unchanged detail but preserves a plugin-selected renamed path`() {
        val oldDirectory = Path.of("library", "Reviews")
        val renamedDirectory = Path.of("library", "Archived reviews")

        assertTrue(
            shouldReconcileFolderDetailAfterReload(
                templateActive = false,
                authorOpen = false,
                previousFolderDirectory = oldDirectory,
                currentFolderDirectory = oldDirectory,
            ),
        )
        assertTrue(
            !shouldReconcileFolderDetailAfterReload(
                templateActive = false,
                authorOpen = false,
                previousFolderDirectory = oldDirectory,
                currentFolderDirectory = renamedDirectory,
            ),
        )
    }

    @Test
    fun `search indexing does not follow a symbolic link masquerading as template Markdown`() {
        val outside = temporaryDirectory.resolve("outside.md")
        val linkedMarkdown = temporaryDirectory.resolve("prompt.md")
        Files.writeString(outside, "private outside content")
        Files.createSymbolicLink(linkedMarkdown, outside)

        assertEquals("private outside content", readSearchIndexBody(outside))
        assertEquals("", readSearchIndexBody(linkedMarkdown))
    }

    @Test
    fun `search indexing rejects a non-regular path before opening it`() {
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

    @Test
    fun `narrow library exposes a return route while an author is open`() {
        assertTrue(authorReturnVisible(narrowMode = true, authorOpen = true))
        assertTrue(!authorReturnVisible(narrowMode = false, authorOpen = true))
        assertTrue(!authorReturnVisible(narrowMode = true, authorOpen = false))
    }
}
