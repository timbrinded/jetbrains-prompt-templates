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
    private val root = Path.of("/library").toAbsolutePath().normalize()

    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `materializes the normalized VFS root before registering the recursive watch`() {
        val unnormalizedRoot = Path.of("library", "nested", "..").toAbsolutePath()
        val normalizedRoot = unnormalizedRoot.normalize()
        val operations = mutableListOf<String>()

        val registration = registerLibraryWatch(
            root = unnormalizedRoot,
            pathExists = { path -> path == normalizedRoot },
            materializeRoot = { path ->
                operations += "materialize:$path"
                "virtual-root"
            },
            addRecursiveWatch = { path, recursive ->
                operations += "watch:$path:$recursive"
                "watch-request"
            },
        )

        assertEquals(
            listOf("materialize:$normalizedRoot", "watch:$normalizedRoot:true"),
            operations,
        )
        assertEquals(normalizedRoot, registration.materializedPath)
        assertEquals("virtual-root", registration.materializedRoot)
        assertEquals("watch-request", registration.watchRequest)
    }

    @Test
    fun `materializes the nearest existing ancestor when the configured root is missing`() {
        val existingAncestor = Path.of("existing-library-parent").toAbsolutePath().normalize()
        val missingRoot = existingAncestor.resolve("not-created/nested-library")
        val operations = mutableListOf<String>()

        val registration = registerLibraryWatch(
            root = missingRoot,
            pathExists = { path -> path == existingAncestor },
            materializeRoot = { path ->
                operations += "materialize:$path"
                path
            },
            addRecursiveWatch = { path, recursive ->
                operations += "watch:$path:$recursive"
                "watch-request"
            },
        )

        assertEquals(existingAncestor, registration.materializedPath)
        assertEquals(existingAncestor, registration.materializedRoot)
        assertEquals(
            listOf("materialize:$existingAncestor", "watch:$missingRoot:true"),
            operations,
        )
    }

    @Test
    fun `reacts to canonical template and order files`() {
        assertTrue(isPromptLibraryChange(root, "/library/review/prompt.md"))
        assertTrue(isPromptLibraryChange(root, "/library/review/prompt.meta.json"))
        assertTrue(isPromptLibraryChange(root, "/library/reviews/.prompt-templates-order.json"))
    }

    @Test
    fun `ignores temporary files unrelated root files nested extras and sibling directories`() {
        assertFalse(isPromptLibraryChange(root, "/library/review/.prompt.md.123.tmp"))
        assertFalse(isPromptLibraryChange(root, "/library/review/notes.txt"))
        assertFalse(isPromptLibraryChange(root, "/library/readme.txt"))
        assertFalse(isPromptLibraryChange(root, "/library-backup/review/prompt.md"))
    }

    @Test
    fun `ignores files and directories inside IDE and version-control metadata`() {
        listOf(".git", ".hg", ".svn", ".idea").forEach { directory ->
            assertFalse(isPromptLibraryChange(root, "/library/$directory/deep/prompt.md"))
            assertFalse(
                isPromptLibraryChange(
                    root,
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
                root,
                eventPaths = listOf("/library/reviews/security"),
                directoryEvent = true,
            ),
        )
        assertTrue(
            isPromptLibraryChange(
                root,
                eventPaths = listOf("/library/reviews/security", "/library/reviews/audits"),
                directoryEvent = true,
            ),
        )
    }

    @Test
    fun `reacts when a directory move crosses the library boundary using old and new paths`() {
        assertTrue(
            isPromptLibraryChange(
                root,
                eventPaths = listOf("/library/reviews", "/archive/reviews"),
                directoryEvent = true,
            ),
        )
        assertTrue(
            isPromptLibraryChange(
                root,
                eventPaths = listOf("/archive/ideas", "/library/ideas"),
                directoryEvent = true,
            ),
        )
        assertFalse(
            isPromptLibraryChange(
                root,
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
    fun `poll snapshot ignores management directories unrelated files and symbolic links`() {
        val library = Files.createDirectory(temporaryDirectory.resolve("library"))
        val baseline = snapshotPromptLibrary(library)

        Files.writeString(library.resolve("README.txt"), "unmanaged")
        val gitDirectory = Files.createDirectories(library.resolve(".git/objects"))
        Files.writeString(gitDirectory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE), "ignored")
        val outside = Files.createDirectories(temporaryDirectory.resolve("outside/template"))
        Files.writeString(outside.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE), "outside")
        Files.createSymbolicLink(library.resolve("linked-template"), outside)

        assertEquals(baseline, snapshotPromptLibrary(library))
    }

    @Test
    fun `poll change tracker establishes a quiet baseline and reports later changes once`() {
        val tracker = LibraryPollChangeTracker()
        val initial = LibraryPollSnapshot(listOf(LibraryPollEntry("", directory = true)))
        val changed = LibraryPollSnapshot(
            listOf(
                LibraryPollEntry("", directory = true),
                LibraryPollEntry("Reviews", directory = true),
            ),
        )

        assertFalse(tracker.record(initial))
        assertFalse(tracker.record(initial))
        assertTrue(tracker.record(changed))
        assertFalse(tracker.record(changed))
    }
}
