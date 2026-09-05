package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LibraryFileWatcherTest {
    private val roots = listOf(Path.of("/library").toAbsolutePath().normalize())

    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `finds the nearest existing ancestor when the configured root is missing`() {
        val existingAncestor = Files.createDirectories(temporaryDirectory.resolve("existing-library-parent"))
        val missingRoot = existingAncestor.resolve("not-created/nested-library")

        assertEquals(existingAncestor, nearestExistingAncestor(missingRoot))
    }

    @Test
    fun `reacts to canonical template and order files`() {
        assertTrue(isPromptLibraryChange(roots, "/library/review/prompt.md"))
        assertTrue(isPromptLibraryChange(roots, "/library/review/prompt.meta.json"))
        assertTrue(isPromptLibraryChange(roots, "/library/reviews/.prompt-templates-order.json"))
    }

    @Test
    fun `ignores temporary files unrelated root files nested extras and sibling directories`() {
        assertFalse(isPromptLibraryChange(roots, "/library/review/.prompt.md.123.tmp"))
        assertFalse(isPromptLibraryChange(roots, "/library/review/notes.txt"))
        assertFalse(isPromptLibraryChange(roots, "/library/readme.txt"))
        assertFalse(isPromptLibraryChange(roots, "/library-backup/review/prompt.md"))
    }

    @Test
    fun `ignores files and directories inside IDE and version-control metadata`() {
        val internalDirectories = listOf(
            ".git",
            ".hg",
            ".svn",
            ".idea",
            "${FileSystemPromptTemplateRepository.DELETE_SCRATCH_PREFIX}1",
            "${FileSystemPromptTemplateRepository.RENAME_SCRATCH_PREFIX}1",
        )
        internalDirectories.forEach { directory ->
            assertFalse(isPromptLibraryChange(roots, "/library/$directory/deep/prompt.md"))
            assertFalse(
                isPromptLibraryChange(
                    roots,
                    eventPaths = listOf("/library/$directory/objects/new-directory"),
                    directoryEvent = true,
                ),
            )
        }
    }

    @Test
    fun `reacts to deep directory create delete and rename events`() {
        assertTrue(
            isPromptLibraryChange(
                roots,
                eventPaths = listOf("/library/reviews/security"),
                directoryEvent = true,
            ),
        )
        assertTrue(
            isPromptLibraryChange(
                roots,
                eventPaths = listOf("/library/reviews/security", "/library/reviews/audits"),
                directoryEvent = true,
            ),
        )
    }

    @Test
    fun `reacts when a directory move crosses the library boundary using old and new paths`() {
        assertTrue(
            isPromptLibraryChange(
                roots,
                eventPaths = listOf("/library/reviews", "/archive/reviews"),
                directoryEvent = true,
            ),
        )
        assertTrue(
            isPromptLibraryChange(
                roots,
                eventPaths = listOf("/archive/ideas", "/library/ideas"),
                directoryEvent = true,
            ),
        )
        assertFalse(
            isPromptLibraryChange(
                roots,
                eventPaths = listOf("/archive/a", "/archive/b"),
                directoryEvent = true,
            ),
        )
    }

    @Test
    fun `poll snapshot detects organiser directory create rename and delete`() {
        val library = Files.createDirectory(temporaryDirectory.resolve("library"))
        val initial = snapshotPromptLibrary(library)

        val reviews = Files.createDirectory(library.resolve("Reviews"))
        val afterCreate = snapshotPromptLibrary(library)
        assertNotEquals(initial, afterCreate)
        assertTrue(afterCreate.entries.any { entry -> entry.directory && entry.relativePath == "Reviews" })

        val archive = Files.move(reviews, library.resolve("Archive"))
        val afterRename = snapshotPromptLibrary(library)
        assertNotEquals(afterCreate, afterRename)
        assertTrue(afterRename.entries.any { entry -> entry.directory && entry.relativePath == "Archive" })
        assertFalse(afterRename.entries.any { entry -> entry.relativePath == "Reviews" })

        Files.delete(archive)
        assertEquals(initial, snapshotPromptLibrary(library))
    }

    @Test
    fun `poll snapshot detects creation of a previously missing library root`() {
        val library = temporaryDirectory.resolve("missing/library")

        assertTrue(snapshotPromptLibrary(library).entries.isEmpty())

        Files.createDirectories(library)

        assertEquals(
            listOf(LibraryPollEntry(relativePath = "", directory = true)),
            snapshotPromptLibrary(library).entries,
        )
    }

    @Test
    fun `poll snapshot detects a journal created and removed between polls`() {
        val library = Files.createDirectory(temporaryDirectory.resolve("library"))
        val template = Files.createDirectory(library.resolve("Template"))
        Files.writeString(template.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE), "unchanged")
        val before = snapshotPromptLibrary(library)
        val modified = Files.getLastModifiedTime(template)
        val journal = Files.writeString(template.resolve(FileSystemPromptTemplateRepository.SAVE_JOURNAL_FILE), "transient")
        Files.delete(journal)
        // Ensure the distinction also holds on filesystems with coarse timestamp precision.
        Files.setLastModifiedTime(template, FileTime.fromMillis(modified.toMillis() + 10_000L))
        val after = snapshotPromptLibrary(library)
        assertEquals(before.entries.filterNot { it.directory }, after.entries.filterNot { it.directory })
        assertNotEquals(before, after)
    }

    @Test
    fun `poll snapshot detects same-size control file modifications from metadata`() {
        val library = Files.createDirectory(temporaryDirectory.resolve("library"))
        val template = Files.createDirectory(library.resolve("Template"))
        val controlFiles = listOf(
            Files.writeString(template.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE), "one"),
            Files.writeString(template.resolve(FileSystemPromptTemplateRepository.METADATA_FILE), "one"),
            Files.writeString(library.resolve(FileSystemPromptTemplateRepository.ORDER_FILE), "one"),
        )
        var previousSnapshot = snapshotPromptLibrary(library)

        controlFiles.forEachIndexed { index, controlFile ->
            val previousModifiedAt = Files.getLastModifiedTime(controlFile)
            Files.writeString(controlFile, "two")
            Files.setLastModifiedTime(
                controlFile,
                FileTime.fromMillis(previousModifiedAt.toMillis() + ((index + 1) * 10_000L)),
            )

            val currentSnapshot = snapshotPromptLibrary(library)
            assertNotEquals(previousSnapshot, currentSnapshot)
            assertEquals(3, currentSnapshot.entries.count { entry -> !entry.directory })
            previousSnapshot = currentSnapshot
        }
    }

    @Test
    fun `poll snapshot stops at Markdown and metadata-only template package boundaries`() {
        val library = Files.createDirectory(temporaryDirectory.resolve("library"))
        val markdownPackage = Files.createDirectory(library.resolve("Markdown package"))
        Files.writeString(markdownPackage.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE), "prompt")
        Files.createDirectories(markdownPackage.resolve("support/deep"))

        val metadataPackage = Files.createDirectory(library.resolve("Metadata package"))
        Files.writeString(metadataPackage.resolve(FileSystemPromptTemplateRepository.METADATA_FILE), "{}")
        Files.createDirectories(metadataPackage.resolve("support/deep"))
        Files.writeString(
            metadataPackage.resolve("support/deep/${FileSystemPromptTemplateRepository.MARKDOWN_FILE}"),
            "nested support file",
        )

        val paths = snapshotPromptLibrary(library).entries.map(LibraryPollEntry::relativePath)

        assertTrue("Markdown package" in paths)
        assertTrue("Markdown package/${FileSystemPromptTemplateRepository.MARKDOWN_FILE}" in paths)
        assertTrue("Metadata package" in paths)
        assertTrue("Metadata package/${FileSystemPromptTemplateRepository.METADATA_FILE}" in paths)
        assertFalse(paths.any { path -> path.startsWith("Markdown package/support") })
        assertFalse(paths.any { path -> path.startsWith("Metadata package/support") })
    }

    @Test
    fun `poll snapshot ignores management scratch unrelated and linked files`() {
        val library = Files.createDirectory(temporaryDirectory.resolve("library"))
        val baseline = snapshotPromptLibrary(library)

        Files.writeString(library.resolve("README.txt"), "unmanaged")
        val gitDirectory = Files.createDirectories(library.resolve(".git/objects"))
        Files.writeString(gitDirectory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE), "ignored")
        val outside = Files.createDirectories(temporaryDirectory.resolve("outside/template"))
        Files.writeString(outside.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE), "outside")
        Files.createSymbolicLink(library.resolve("linked-template"), outside)
        listOf(
            FileSystemPromptTemplateRepository.DELETE_SCRATCH_PREFIX,
            FileSystemPromptTemplateRepository.RENAME_SCRATCH_PREFIX,
        ).forEach { prefix ->
            val scratch = Files.createDirectories(library.resolve("${prefix}1234/review"))
            Files.writeString(scratch.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE), "retained")
        }

        assertEquals(baseline, snapshotPromptLibrary(library))
    }

    @Test
    fun `poll snapshot follows a symbolic-link library root`() {
        val target = Files.createDirectory(temporaryDirectory.resolve("target-library"))
        val template = Files.createDirectories(target.resolve("Reviews/review-implementation"))
        Files.writeString(template.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE), "content")
        val linkedRoot = Files.createSymbolicLink(temporaryDirectory.resolve("linked-root"), target)

        val linkedSnapshot = snapshotPromptLibrary(linkedRoot)

        assertTrue("Reviews" in linkedSnapshot.entries.map(LibraryPollEntry::relativePath), "Snapshot was $linkedSnapshot")
        assertEquals(snapshotPromptLibrary(target), linkedSnapshot)
    }

    @Test
    fun `reacts to events reported under either path of a symbolic-link root`() {
        val target = Files.createDirectory(temporaryDirectory.resolve("target-library")).toRealPath()
        val linkedRoot = Files.createSymbolicLink(temporaryDirectory.resolve("linked-root"), target)
        val roots = requireNotNull(libraryRootsOrNull(linkedRoot))

        assertTrue(isPromptLibraryChange(roots, target.resolve("Reviews/prompt.md").toString()))
        assertTrue(isPromptLibraryChange(roots, linkedRoot.resolve("Reviews/prompt.md").toString()))
        assertFalse(isPromptLibraryChange(roots, target.resolveSibling("elsewhere/prompt.md").toString()))
    }
}
