package dev.timbrinded.prompttemplates.core

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.useDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSystemPromptTemplateHierarchyTest(
    @param:TempDir private val temporaryDirectory: Path,
) {
    @Test
    fun `scans implicit folders recursively and stops at template packages`() {
        val root = temporaryDirectory.resolve("library")
        val security = root.resolve("Reviews/Security")
        val nestedTemplate = writeTemplate(security.resolve("audit"), "Audit", TemplateId.random())
        Files.createDirectories(root.resolve("Empty"))
        val packageDirectory = root.resolve("Package")
        Files.createDirectories(packageDirectory.resolve("assets/nested-template"))
        Files.writeString(packageDirectory.resolve("prompt.md"), "# Package")
        Files.writeString(packageDirectory.resolve("assets/nested-template/prompt.md"), "Must stay opaque")

        val snapshot = FileSystemPromptTemplateRepository(root).scan()

        assertEquals(root.toAbsolutePath(), snapshot.root)
        assertEquals(listOf("Empty", "Reviews", "Package"), snapshot.children.map(LibraryEntry::displayName))
        val reviews = assertIs<LibraryEntry.Folder>(snapshot.children[1])
        val securityEntry = assertIs<LibraryEntry.Folder>(reviews.children.single())
        val audit = assertIs<LibraryEntry.Template>(securityEntry.children.single())
        assertEquals(nestedTemplate.toAbsolutePath(), audit.directory)
        assertEquals(Path.of("Reviews", "Security", "audit"), audit.relativeDirectory)
        assertIs<LibraryEntry.Template>(snapshot.children[2])
    }

    @Test
    fun `excludes IDE and version-control metadata directories from the managed tree`() {
        val root = temporaryDirectory.resolve("library")
        val managementNames = listOf(".git", ".hg", ".svn", ".idea")
        managementNames.forEach { name ->
            val hiddenTemplate = root.resolve("$name/deep/template")
            Files.createDirectories(hiddenTemplate)
            Files.writeString(hiddenTemplate.resolve("prompt.md"), "must stay unmanaged")
            val nestedManagement = root.resolve("Visible/$name")
            Files.createDirectories(nestedManagement)
        }
        Files.createDirectories(root.resolve(".private-notes"))
        val repository = FileSystemPromptTemplateRepository(root)

        val snapshot = repository.scan()

        assertEquals(listOf(".private-notes", "Visible"), snapshot.children.map(LibraryEntry::displayName))
        assertTrue(assertIs<LibraryEntry.Folder>(snapshot.children[1]).children.isEmpty())
        managementNames.forEach { name ->
            assertIs<RepositoryResult.Failure>(repository.createFolder(root, name.uppercase()))
            assertIs<RepositoryResult.Failure>(repository.moveEntry(root.resolve(name), root))
            assertIs<RepositoryResult.Failure>(repository.createFolder(root.resolve("Visible/$name"), "Nested"))
        }
    }

    @Test
    fun `hides the repository's own scratch directories and refuses to manage them`() {
        val root = temporaryDirectory.resolve("library")
        val scratchNames = listOf(
            "${FileSystemPromptTemplateRepository.DELETE_SCRATCH_PREFIX}1234",
            "${FileSystemPromptTemplateRepository.RENAME_SCRATCH_PREFIX}5678",
        )
        scratchNames.forEach { name ->
            val retained = root.resolve("$name/review")
            Files.createDirectories(retained)
            Files.writeString(retained.resolve("prompt.md"), "retained for recovery")
        }
        Files.createDirectories(root.resolve("Visible/${FileSystemPromptTemplateRepository.RENAME_SCRATCH_PREFIX}nested"))
        val repository = FileSystemPromptTemplateRepository(root)

        val snapshot = repository.scan()
        assertEquals(listOf("Visible"), snapshot.children.map(LibraryEntry::displayName))
        scratchNames.forEach { assertTrue(snapshot.diagnostic.orEmpty().contains("'$it'"), snapshot.diagnostic) }
        assertTrue(snapshot.diagnostic.orEmpty().contains("interrupted rename or deletion"))
        assertTrue(assertIs<LibraryEntry.Folder>(snapshot.children.single()).diagnostic.orEmpty().contains("nested'"))
        scratchNames.forEach { name ->
            assertIs<RepositoryResult.Failure>(repository.createFolder(root, "$name-new"))
            assertFalse(Files.exists(root.resolve("$name-new")))
            assertIs<RepositoryResult.Failure>(repository.renameFolder(root.resolve("Visible"), name))
            assertTrue(Files.isDirectory(root.resolve("Visible")))
            assertIs<RepositoryResult.Failure>(repository.moveEntry(root.resolve(name), root.resolve("Visible")))
            assertIs<RepositoryResult.Failure>(repository.previewFolderDeletion(root.resolve(name)))
        }
    }

    @Test
    fun `classifies a package from either canonical entry and rejects canonical symlinks`() {
        val root = temporaryDirectory.resolve("library")
        val metadataOnly = root.resolve("metadata-only")
        val linkedMarkdown = root.resolve("linked-markdown")
        Files.createDirectories(metadataOnly)
        Files.writeString(
            metadataOnly.resolve("prompt.meta.json"),
            TemplateMetadataCodec().encode(metadata("Metadata only", TemplateId.random())),
        )
        Files.createDirectories(linkedMarkdown)
        val outside = temporaryDirectory.resolve("outside.md")
        Files.writeString(outside, "outside")
        createSymbolicLinkOrSkip(linkedMarkdown.resolve("prompt.md"), outside)

        val entries = FileSystemPromptTemplateRepository(root).scan().children

        val metadataEntry = assertIs<LibraryEntry.Template>(entries.first { it.displayName == "Metadata only" })
        assertEquals(TemplateHealth.BROKEN, metadataEntry.summary.health)
        val linkedEntry = assertIs<LibraryEntry.Template>(entries.first { it.displayName == "linked-markdown" })
        assertEquals(TemplateHealth.BROKEN, linkedEntry.summary.health)
        assertTrue(linkedEntry.summary.diagnostic.orEmpty().contains("regular file"))
        assertIs<RepositoryResult.Failure>(FileSystemPromptTemplateRepository(root).load(linkedMarkdown))
    }

    @Test
    fun `uses folder-first alphabetical order for a legacy library`() {
        val root = temporaryDirectory.resolve("library")
        Files.createDirectories(root.resolve("Zulu"))
        Files.createDirectories(root.resolve("alpha"))
        writeTemplate(root.resolve("z-template"), "Zulu template", TemplateId.random())
        writeTemplate(root.resolve("a-template"), "Alpha template", TemplateId.random())

        val children = FileSystemPromptTemplateRepository(root).scan().children

        assertEquals(listOf("alpha", "Zulu", "Alpha template", "Zulu template"), children.map(LibraryEntry::displayName))
        assertFalse(Files.exists(root.resolve(FileSystemPromptTemplateRepository.ORDER_FILE)))
    }

    @Test
    fun `legacy fallback sorts templates by visible name rather than directory segment`() {
        val root = temporaryDirectory.resolve("library")
        writeTemplate(root.resolve("aaa"), "Zulu", TemplateId.random())
        writeTemplate(root.resolve("zzz"), "Alpha", TemplateId.random())

        assertEquals(listOf("Alpha", "Zulu"), FileSystemPromptTemplateRepository(root).scan().children.map(LibraryEntry::displayName))
    }

    @Test
    fun `falls back to alphabetical order for unreadable order data`() {
        val root = temporaryDirectory.resolve("library")
        Files.createDirectories(root.resolve("Zulu"))
        Files.createDirectories(root.resolve("Alpha"))
        val repository = FileSystemPromptTemplateRepository(root)
        val orderPath = root.resolve(FileSystemPromptTemplateRepository.ORDER_FILE)

        // Not JSON.
        Files.writeString(orderPath, "not-json")
        val malformed = repository.scan()
        assertEquals(listOf("Alpha", "Zulu"), malformed.children.map(LibraryEntry::displayName))
        assertTrue(malformed.diagnostic.orEmpty().contains("invalid"))

        // Unsupported schema version.
        Files.writeString(orderPath, """{"schemaVersion": 2, "folders": ["Zulu"]}""")
        val unsupported = repository.scan()
        assertEquals(listOf("Alpha", "Zulu"), unsupported.children.map(LibraryEntry::displayName))
        assertTrue(unsupported.diagnostic.orEmpty().contains("Unsupported"))

        // Not valid UTF-8.
        Files.write(orderPath, byteArrayOf(0xC3.toByte()))
        val unreadable = repository.scan()
        assertEquals(listOf("Alpha", "Zulu"), unreadable.children.map(LibraryEntry::displayName))
        assertTrue(unreadable.diagnostic.orEmpty().startsWith("Unable to read"))
    }

    @Test
    fun `never replaces an unreadable or newer order file`() {
        val root = temporaryDirectory.resolve("library")
        Files.createDirectories(root.resolve("Alpha"))
        Files.createDirectories(root.resolve("Bravo"))
        val repository = FileSystemPromptTemplateRepository(root)
        val orderPath = root.resolve(FileSystemPromptTemplateRepository.ORDER_FILE)
        val newer = """{"schemaVersion": 2, "folders": ["Bravo", "Alpha"], "pinned": ["Bravo"]}"""
        Files.writeString(orderPath, newer)

        val created = assertIs<RepositoryResult.Success<Path>>(repository.createFolder(root, "Charlie"))

        assertTrue(Files.isDirectory(created.value))
        assertTrue(created.warnings.single().contains("left unchanged"), created.warnings.toString())
        assertEquals(newer, orderPath.readText())

        Files.writeString(orderPath, "{ malformed")
        val reorder = repository.moveEntry(root.resolve("Bravo"), root, EntryPlacement.Before(root.resolve("Alpha")))

        assertTrue(assertIs<RepositoryResult.Failure>(reorder).message.contains("left unchanged"))
        assertEquals("{ malformed", orderPath.readText())
    }

    @Test
    fun `uses a portable manual order and appends unlisted entries alphabetically`() {
        val root = temporaryDirectory.resolve("library")
        Files.createDirectories(root.resolve("Alpha"))
        Files.createDirectories(root.resolve("Bravo"))
        Files.createDirectories(root.resolve("Zulu"))
        writeTemplate(root.resolve("a-template"), "Alpha template", TemplateId.random())
        writeTemplate(root.resolve("b-template"), "Bravo template", TemplateId.random())
        writeTemplate(root.resolve("z-template"), "Zulu template", TemplateId.random())
        Files.writeString(
            root.resolve(FileSystemPromptTemplateRepository.ORDER_FILE),
            """
            {
              "schemaVersion": 1,
              "folders": ["Zulu", "missing"],
              "templates": ["z-template"]
            }
            """.trimIndent(),
        )

        val snapshot = FileSystemPromptTemplateRepository(root).scan()

        assertEquals(
            listOf("Zulu", "Alpha", "Bravo", "Zulu template", "Alpha template", "Bravo template"),
            snapshot.children.map(LibraryEntry::displayName),
        )
        assertNull(snapshot.diagnostic)
    }

    @Test
    fun `creates and imports templates in nested folders`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val reviews = success(repository.createFolder(root, "Reviews"))
        val security = success(repository.createFolder(reviews, "Security"))
        success(
            repository.create(
                PromptTemplateDraft(name = "Audit", markdown = "Review {{scope}}"),
                security,
            ),
        )
        val source = temporaryDirectory.resolve("source.md")
        Files.writeString(source, "# Imported\n\nUse {{value}}")
        val imported = success(repository.importMarkdown(source, security))

        assertEquals(security, imported.directory.parent)
        assertEquals(listOf("Audit", "Imported"), folder(repository.scan(), "Reviews", "Security").children.map(LibraryEntry::displayName))
    }

    @Test
    fun `allows equal template names in separate folders but enforces sibling names and global ids`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val firstFolder = success(repository.createFolder(root, "First"))
        val secondFolder = success(repository.createFolder(root, "Second"))
        val sharedId = TemplateId.random()
        success(repository.create(PromptTemplateDraft(id = sharedId, name = "Review", markdown = "one"), firstFolder))
        success(repository.create(PromptTemplateDraft(name = "Review", markdown = "two"), secondFolder))

        assertIs<RepositoryResult.Failure>(
            repository.create(PromptTemplateDraft(name = " review ", markdown = "duplicate"), firstFolder),
        )
        assertIs<RepositoryResult.Failure>(
            repository.create(
                PromptTemplateDraft(
                    id = TemplateId(sharedId.value.uppercase()),
                    name = "Unique",
                    markdown = "duplicate id",
                ),
                secondFolder,
            ),
        )
        assertIs<RepositoryResult.Failure>(repository.createFolder(firstFolder, "Review"))
    }

    @Test
    fun `marks externally introduced sibling-name and global-id conflicts`() {
        val root = temporaryDirectory.resolve("library")
        val duplicateId = TemplateId.random()
        writeTemplate(root.resolve("one"), "Same", duplicateId)
        writeTemplate(root.resolve("two"), "same", duplicateId)
        Files.createDirectories(root.resolve("SAME"))

        val children = FileSystemPromptTemplateRepository(root).scan().children

        assertTrue(assertIs<LibraryEntry.Folder>(children.first()).diagnostic.orEmpty().contains("Duplicate sibling"))
        children.filterIsInstance<LibraryEntry.Template>().forEach { entry ->
            assertEquals(TemplateHealth.BROKEN, entry.summary.health)
            assertTrue(entry.summary.diagnostic.orEmpty().contains("Duplicate template UUID"))
            assertTrue(entry.summary.diagnostic.orEmpty().contains("Duplicate sibling"))
        }
    }

    @Test
    fun `moves and reorders templates while preserving package bytes and UUID`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val source = success(repository.createFolder(root, "Source"))
        val destination = success(repository.createFolder(root, "Destination"))
        val first = success(repository.create(PromptTemplateDraft(name = "First", markdown = "first"), destination))
        val second = success(repository.create(PromptTemplateDraft(name = "Second", markdown = "second"), destination))
        val moving = success(repository.create(PromptTemplateDraft(name = "Moving", markdown = "move me"), source))
        Files.writeString(moving.directory.resolve("support.txt"), "support")

        success(repository.moveEntry(second.directory, destination, EntryPlacement.Before(first.directory)))
        assertEquals(
            listOf("Second", "First"),
            folder(repository.scan(), "Destination").children.map(LibraryEntry::displayName),
        )

        val moved = success(repository.moveEntry(moving.directory, destination, EntryPlacement.After(first.directory)))
        assertFalse(Files.exists(moving.directory))
        assertEquals("support", moved.resolve("support.txt").readText())
        assertEquals(moving.template.id, success(repository.load(moved)).template.id)
        assertEquals(
            listOf("Second", "First", "Moving"),
            folder(repository.scan(), "Destination").children.map(LibraryEntry::displayName),
        )
    }

    @Test
    fun `moves a template past a hidden directory-name collision and records the new name`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val source = success(repository.createFolder(root, "Source"))
        val destination = success(repository.createFolder(root, "Destination"))
        val existing = success(repository.create(PromptTemplateDraft(name = "Review?", markdown = "stays"), destination))
        val moving = success(repository.create(PromptTemplateDraft(name = "Review!", markdown = "moves"), source))
        assertEquals(existing.directory.name, moving.directory.name)

        val moved = success(repository.moveEntry(moving.directory, destination))

        assertEquals(destination.resolve("${existing.directory.name}-2"), moved)
        assertEquals("moves", success(repository.load(moved)).template.markdown)
        assertEquals("stays", success(repository.load(existing.directory)).template.markdown)
        assertEquals(
            listOf(existing.directory.name, moved.name),
            requireNotNull(LibraryFolderOrderCodec.read(destination).value).templates,
        )
        assertEquals(listOf("Review?", "Review!"), folder(repository.scan(), "Destination").children.map(LibraryEntry::displayName))
    }

    @Test
    fun `moves and renames folders while preserving their child order`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val source = success(repository.createFolder(root, "Source"))
        val destination = success(repository.createFolder(root, "Destination"))
        val nested = success(repository.createFolder(source, "Nested"))
        val first = success(repository.create(PromptTemplateDraft(name = "First", markdown = "1"), nested))
        val second = success(repository.create(PromptTemplateDraft(name = "Second", markdown = "2"), nested))
        success(repository.moveEntry(second.directory, nested, EntryPlacement.Before(first.directory)))

        val moved = success(repository.moveEntry(nested, destination))
        val renamed = success(repository.renameFolder(moved, "Renamed"))

        assertFalse(Files.exists(nested))
        assertEquals(destination.resolve("Renamed"), renamed)
        assertEquals(
            listOf("Second", "First"),
            folder(repository.scan(), "Destination", "Renamed").children.map(LibraryEntry::displayName),
        )
    }

    @Test
    fun `renames folder casing and updates the stored order key after success`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val reviews = success(repository.createFolder(root, "reviews"))
        val audit = success(repository.create(PromptTemplateDraft(name = "Audit", markdown = "audit"), reviews))

        // Case-insensitive filesystems (Windows, default macOS) see both names as one entry and take the two-step
        // rename through a hidden working directory; case-sensitive ones take an ordinary rename.
        val renamed = success(repository.renameFolder(reviews, "Reviews"))

        assertEquals(root.resolve("Reviews"), renamed)
        val snapshot = repository.scan()
        assertEquals(listOf("Reviews"), snapshot.children.map(LibraryEntry::displayName))
        assertNull(snapshot.diagnostic)
        assertEquals(audit.template.id, success(repository.load(renamed.resolve(audit.directory.name))).template.id)
        assertEquals(
            listOf("Reviews"),
            requireNotNull(LibraryFolderOrderCodec.read(root).value).folders,
        )
        val directoryNames = root.useDirectoryEntries { entries ->
            entries
                .filter { Files.isDirectory(it) }
                .map { it.name }
                .toList()
        }
        assertEquals(listOf("Reviews"), directoryNames)
    }

    @Test
    fun `rejects cycles collisions unsafe paths and cross-kind placement`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val parent = success(repository.createFolder(root, "Parent"))
        val child = success(repository.createFolder(parent, "Child"))
        val destination = success(repository.createFolder(root, "Destination"))
        val template = success(repository.create(PromptTemplateDraft(name = "Template", markdown = "x"), parent))
        val destinationTemplate = success(
            repository.create(PromptTemplateDraft(name = "Other", markdown = "y"), destination),
        )
        success(repository.createFolder(destination, "Template"))
        Files.writeString(root.resolve("Occupied"), "file")

        assertIs<RepositoryResult.Failure>(repository.renameFolder(parent, "Occupied"))
        assertIs<RepositoryResult.Failure>(repository.moveEntry(parent, child))
        assertIs<RepositoryResult.Failure>(repository.moveEntry(template.directory, destination))
        assertIs<RepositoryResult.Failure>(
            repository.moveEntry(template.directory, parent, EntryPlacement.Before(child)),
        )
        assertIs<RepositoryResult.Failure>(repository.moveEntry(temporaryDirectory, destination))
        assertIs<RepositoryResult.Failure>(repository.moveEntry(destinationTemplate.directory, template.directory))
        assertTrue(Files.exists(parent))
        assertTrue(Files.exists(template.directory))
    }

    @Test
    fun `rejects mutations below an opaque template package`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val templatePackage = success(
            repository.create(PromptTemplateDraft(name = "Package", markdown = "package")),
        ).directory
        val supportDirectory = templatePackage.resolve("assets/deep")
        Files.createDirectories(supportDirectory)
        val organiser = success(repository.createFolder(root, "Organiser"))
        val movingTemplate = success(
            repository.create(PromptTemplateDraft(name = "Moving", markdown = "moving"), organiser),
        )

        assertIs<RepositoryResult.Failure>(
            repository.create(PromptTemplateDraft(name = "Hidden", markdown = "hidden"), supportDirectory),
        )
        assertIs<RepositoryResult.Failure>(repository.createFolder(supportDirectory, "Hidden folder"))
        assertIs<RepositoryResult.Failure>(repository.moveEntry(movingTemplate.directory, supportDirectory))
        assertIs<RepositoryResult.Failure>(repository.moveEntry(supportDirectory, organiser))

        assertTrue(Files.exists(movingTemplate.directory))
        assertTrue(Files.exists(supportDirectory))
        assertFalse(Files.exists(supportDirectory.resolve("Hidden folder")))
        assertEquals(
            listOf("Organiser", "Package"),
            repository.scan().children.map(LibraryEntry::displayName),
        )
    }

    @Test
    fun `rechecks a recursive deletion preview and never follows support symlinks`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val folder = success(repository.createFolder(root, "Delete me"))
        val nested = success(repository.createFolder(folder, "Nested"))
        val stored = success(repository.create(PromptTemplateDraft(name = "Template", markdown = "before"), nested))
        val outside = temporaryDirectory.resolve("outside.txt")
        Files.writeString(outside, "keep")
        createSymbolicLinkOrSkip(stored.directory.resolve("outside-link"), outside)

        val firstPreview = success(repository.previewFolderDeletion(folder))
        assertTrue(firstPreview.folderCount >= 1)
        assertEquals(1, firstPreview.templateCount)
        assertTrue(firstPreview.fileCount >= 4)
        Files.writeString(stored.directory.resolve("prompt.md"), "after")

        val changed = repository.deleteFolder(firstPreview)
        assertIs<RepositoryResult.Failure>(changed)
        assertTrue(Files.exists(folder))
        assertEquals("keep", outside.readText())

        val currentPreview = success(repository.previewFolderDeletion(folder))
        assertNotEquals(firstPreview.fingerprint, currentPreview.fingerprint)
        success(repository.deleteFolder(currentPreview))
        assertFalse(Files.exists(folder))
        assertEquals("keep", outside.readText())
        assertIs<RepositoryResult.Failure>(repository.previewFolderDeletion(root))
    }

    @Test
    fun `deletion preview treats a template support tree as opaque but fingerprints its files`() {
        val root = temporaryDirectory.resolve("library")
        val folder = root.resolve("Delete me")
        val template = folder.resolve("template")
        val nestedPackage = template.resolve("support/nested-package")
        Files.createDirectories(nestedPackage)
        Files.writeString(template.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE), "prompt")
        val supportFile = template.resolve("support/data.txt")
        Files.writeString(supportFile, "before")
        Files.writeString(
            nestedPackage.resolve(FileSystemPromptTemplateRepository.METADATA_FILE),
            "nested package metadata",
        )
        val repository = FileSystemPromptTemplateRepository(root)

        val firstPreview = success(repository.previewFolderDeletion(folder))

        assertEquals(0, firstPreview.folderCount)
        assertEquals(1, firstPreview.templateCount)
        assertEquals(3, firstPreview.fileCount)

        Files.writeString(supportFile, "after")
        val changedPreview = success(repository.previewFolderDeletion(folder))

        assertEquals(0, changedPreview.folderCount)
        assertEquals(1, changedPreview.templateCount)
        assertNotEquals(firstPreview.fingerprint, changedPreview.fingerprint)
    }

    @Test
    fun `forced Windows-capable fallback deletes a nested tree and never follows an intermediate replaced by a link`() {
        val target = temporaryDirectory.resolve("target")
        val intermediate = target.resolve("intermediate")
        val displaced = temporaryDirectory.resolve("displaced")
        val outside = temporaryDirectory.resolve("outside")
        Files.createDirectories(intermediate)
        Files.createDirectories(target.resolve("one/two"))
        Files.createDirectories(outside)
        Files.writeString(target.resolve("root.txt"), "root")
        Files.writeString(target.resolve("one/two/deep.txt"), "deep")
        Files.writeString(intermediate.resolve("victim.txt"), "original")
        Files.writeString(outside.resolve("victim.txt"), "outside")
        // Deletion traverses afresh, so a directory swapped for a link after the preview is removed as a link.
        Files.move(intermediate, displaced)
        createSymbolicLinkOrSkip(intermediate, outside)

        LibraryTreeDeletion.deleteTree(target, LibraryDeletionMode.CONSERVATIVE_FALLBACK)

        assertFalse(Files.exists(target))
        assertEquals("outside", outside.resolve("victim.txt").readText())
        assertEquals("original", displaced.resolve("victim.txt").readText())
        assertFalse(hasQuarantine(temporaryDirectory))
    }

    @Test
    fun `forced fallback deletion restores the target when a nested entry cannot be deleted`() {
        assumePosixPermissions()
        val target = temporaryDirectory.resolve("target")
        val locked = target.resolve("locked")
        Files.createDirectories(locked)
        Files.writeString(target.resolve("first.txt"), "first")
        Files.writeString(locked.resolve("kept.txt"), "kept")
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assumeFalse(Files.isWritable(locked), "Directory permissions do not restrict this user.")

            val failure = assertFailsWith<IOException> {
                LibraryTreeDeletion.deleteTree(target, LibraryDeletionMode.CONSERVATIVE_FALLBACK)
            }

            assertTrue(failure.message.orEmpty().contains("restored to '$target'"), failure.message)
            assertEquals("kept", locked.resolve("kept.txt").readText())
            assertFalse(hasQuarantine(temporaryDirectory))
        } finally {
            if (Files.exists(locked)) Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `template deletion refuses support entries and never follows a canonical symlink`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val folder = success(repository.createFolder(root, "Folder"))
        val stored = success(repository.create(PromptTemplateDraft(name = "Template", markdown = "body"), folder))
        val outside = temporaryDirectory.resolve("outside")
        Files.createDirectories(outside)
        Files.writeString(outside.resolve("keep.txt"), "keep")
        createSymbolicLinkOrSkip(stored.directory.resolve("support"), outside)

        val refused = assertIs<RepositoryResult.Failure>(repository.deleteTemplate(stored.directory))

        assertTrue(refused.message.contains("'support'"), refused.message)
        assertTrue(Files.isRegularFile(stored.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE)))
        Files.delete(stored.directory.resolve("support"))
        Files.delete(stored.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE))
        createSymbolicLinkOrSkip(stored.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE), outside.resolve("keep.txt"))

        success(repository.deleteTemplate(stored.directory))

        assertFalse(Files.exists(stored.directory))
        assertEquals("keep", outside.resolve("keep.txt").readText())
    }

    @Test
    fun `refuses to delete an organiser folder that only looks like a template because of a stray prompt`() {
        val root = temporaryDirectory.resolve("library")
        val reviews = root.resolve("Reviews")
        val nested = writeTemplate(reviews.resolve("Security/audit"), "Audit", TemplateId.random())
        writeTemplate(reviews.resolve("archive/old"), "Old", TemplateId.random())
        Files.writeString(reviews.resolve("notes.txt"), "notes")
        Files.writeString(reviews.resolve("todo.md"), "todo")
        Files.writeString(reviews.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE), "stray")
        val repository = FileSystemPromptTemplateRepository(root)
        assertIs<LibraryEntry.Template>(repository.scan().children.single())

        val failure = assertIs<RepositoryResult.Failure>(repository.deleteTemplate(reviews))

        assertTrue(failure.message.contains("'archive', 'notes.txt', 'Security' and 1 more"), failure.message)
        assertTrue(failure.message.contains("file manager"))
        assertEquals("stray", reviews.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE).readText())
        assertEquals("notes", reviews.resolve("notes.txt").readText())
        assertEquals("# Audit", nested.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE).readText())
        assertTrue(Files.isRegularFile(reviews.resolve("archive/old").resolve(FileSystemPromptTemplateRepository.METADATA_FILE)))
    }

    @Test
    fun `template deletion accepts save working files and OS metadata`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val stored = success(repository.create(PromptTemplateDraft(name = "Template", markdown = "body")))
        listOf(".DS_Store", "Thumbs.db", "desktop.ini", "._prompt.md", "${LibraryLayout.STAGE_PREFIX}leftover.tmp").forEach {
            Files.writeString(stored.directory.resolve(it), "")
        }

        success(repository.deleteTemplate(stored.directory, stored.template.id))

        assertFalse(Files.exists(stored.directory))
    }

    @Test
    fun `template deletion with an expected id refuses a different or unreadable template at the same path`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val stored = success(repository.create(PromptTemplateDraft(name = "Review", markdown = "old")))
        val metadataPath = stored.directory.resolve(FileSystemPromptTemplateRepository.METADATA_FILE)
        // Another IDE moved the template away and created a different one with the same slug.
        Files.writeString(metadataPath, TemplateMetadataCodec().encode(metadata("Review", TemplateId.random())))

        val replaced = assertIs<RepositoryResult.Failure>(repository.deleteTemplate(stored.directory, stored.template.id))
        assertTrue(replaced.message.contains("changed on disk"), replaced.message)
        Files.writeString(metadataPath, "not metadata")
        assertIs<RepositoryResult.Failure>(repository.deleteTemplate(stored.directory, stored.template.id))
        assertTrue(Files.isRegularFile(metadataPath))

        Files.delete(metadataPath)
        val recoverable = success(repository.load(stored.directory))
        success(repository.deleteTemplate(stored.directory, recoverable.template.id))
        assertFalse(Files.exists(stored.directory))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `deleting a folder removes directory junctions without touching their targets`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val team = success(repository.createFolder(root, "Team"))
        success(repository.create(PromptTemplateDraft(name = "Review", markdown = "body"), team))
        val outside = temporaryDirectory.resolve("team-prompts")
        Files.createDirectories(outside.resolve("nested"))
        Files.writeString(outside.resolve("keep.txt"), "keep")
        Files.writeString(outside.resolve("nested/prompt.md"), "outside template")
        val removed = temporaryDirectory.resolve("removed")
        Files.createDirectories(removed)
        createJunction(team.resolve("Shared"), outside)
        createJunction(team.resolve("Loop"), root)
        createJunction(team.resolve("Dangling"), removed)
        Files.delete(removed)

        val linked = folder(repository.scan(), "Team").children.filter { it.displayName in setOf("Shared", "Loop", "Dangling") }
        assertEquals(3, linked.size)
        linked.forEach { assertTrue(assertIs<LibraryEntry.Folder>(it).diagnostic.orEmpty().contains("junction")) }
        assertIs<RepositoryResult.Failure>(repository.createFolder(team.resolve("Shared"), "Escaped"))
        assertFalse(Files.exists(outside.resolve("Escaped")))

        val preview = success(repository.previewFolderDeletion(team))
        assertEquals(1, preview.templateCount)
        success(repository.deleteFolder(preview))

        assertFalse(Files.exists(team))
        assertTrue(Files.isDirectory(root))
        assertEquals("keep", outside.resolve("keep.txt").readText())
        assertEquals("outside template", outside.resolve("nested/prompt.md").readText())
    }

    @Test
    fun `reports an order warning after a successful content mutation`() {
        val root = temporaryDirectory.resolve("library")
        Files.createDirectories(root.resolve(FileSystemPromptTemplateRepository.ORDER_FILE))
        val repository = FileSystemPromptTemplateRepository(root)

        val result = assertIs<RepositoryResult.Success<Path>>(repository.createFolder(root, "Created"))

        assertTrue(Files.isDirectory(result.value))
        assertTrue(result.warnings.single().contains("order could not be saved"))
        assertTrue(repository.scan().diagnostic.orEmpty().contains("not a regular file"))
    }

    @Test
    fun `returns failure when a pure reorder cannot persist its order`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val first = success(repository.create(PromptTemplateDraft(name = "First", markdown = "first")))
        val second = success(repository.create(PromptTemplateDraft(name = "Second", markdown = "second")))
        val orderPath = root.resolve(FileSystemPromptTemplateRepository.ORDER_FILE)
        Files.delete(orderPath)
        Files.createDirectory(orderPath)

        val result = repository.moveEntry(second.directory, root, EntryPlacement.Before(first.directory))

        val failure = assertIs<RepositoryResult.Failure>(result)
        assertTrue(failure.message.contains("Unable to move library entry"))
        assertTrue(Files.exists(first.directory))
        assertTrue(Files.exists(second.directory))
        assertEquals(
            listOf("First", "Second"),
            repository.scan().children.map(LibraryEntry::displayName),
        )
    }

    @Test
    fun `rejects symlink paths and portable reserved folder names`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val safe = success(repository.createFolder(root, "Safe"))
        val sub = success(repository.createFolder(safe, "Sub"))
        val outside = temporaryDirectory.resolve("outside")
        Files.createDirectories(outside)
        val link = root.resolve("Link")
        createSymbolicLinkOrSkip(link, outside)
        // An alias inside the library passes the real-path containment check, so only the per-segment guard stops it.
        createSymbolicLinkOrSkip(root.resolve("Alias"), safe)

        assertIs<RepositoryResult.Failure>(repository.createFolder(link, "Escaped"))
        assertFalse(Files.exists(outside.resolve("Escaped")))
        assertIs<RepositoryResult.Failure>(repository.createFolder(root.resolve("Alias").resolve("Sub"), "Aliased"))
        assertFalse(Files.exists(sub.resolve("Aliased")))
        assertIs<RepositoryResult.Failure>(repository.createFolder(root, "prompt.md"))
        assertIs<RepositoryResult.Failure>(repository.createFolder(root, "bad/name"))
    }

    @Test
    fun `rejects Windows device names and keeps generated directory names short`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)

        listOf("CON", "nul.txt", "Com1", "lpt9.tar.gz", "x".repeat(256)).forEach { name ->
            assertIs<RepositoryResult.Failure>(repository.createFolder(root, name), name)
        }
        success(repository.createFolder(root, "Console"))
        val device = success(repository.create(PromptTemplateDraft(name = "Con", markdown = "device")))
        val long = success(repository.create(PromptTemplateDraft(name = "word ".repeat(40), markdown = "long")))

        assertEquals("con-template", device.directory.name)
        assertEquals("word-".repeat(12) + "word", long.directory.name)
        assertEquals(listOf("Console"), repository.scan().children.filterIsInstance<LibraryEntry.Folder>().map { it.displayName })
    }

    @Test
    fun `supports an explicitly configured symlink library root`() {
        val physicalRoot = temporaryDirectory.resolve("physical-library")
        Files.createDirectories(physicalRoot)
        val linkedRoot = temporaryDirectory.resolve("linked-library")
        createSymbolicLinkOrSkip(linkedRoot, physicalRoot)
        val repository = FileSystemPromptTemplateRepository(linkedRoot)

        val folder = success(repository.createFolder(linkedRoot, "Folder"))
        val template = success(repository.create(PromptTemplateDraft(name = "Template", markdown = "body"), folder))

        assertTrue(template.directory.startsWith(linkedRoot))
        assertTrue(Files.isRegularFile(physicalRoot.resolve("Folder/template/prompt.md")))
        assertEquals(listOf("Folder"), repository.scan().children.map(LibraryEntry::displayName))
    }

    @Test
    fun `serializes mutations across repository instances and root aliases`() {
        val physicalParent = temporaryDirectory.resolve("physical")
        Files.createDirectories(physicalParent)
        val aliasParent = temporaryDirectory.resolve("alias")
        createSymbolicLinkOrSkip(aliasParent, physicalParent)
        val physicalRoot = physicalParent.resolve("library")
        val aliasRoot = aliasParent.resolve("library")
        val repositories = listOf(
            FileSystemPromptTemplateRepository(physicalRoot),
            FileSystemPromptTemplateRepository(aliasRoot),
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)

        try {
            val futures = (0 until 24).map { index ->
                executor.submit<RepositoryResult<StoredTemplate>> {
                    start.await()
                    repositories[index % repositories.size].create(
                        PromptTemplateDraft(
                            name = "Template ${index.toString().padStart(2, '0')}",
                            markdown = "body $index",
                        ),
                    )
                }
            }
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }

            results.forEach { result ->
                val success = assertIs<RepositoryResult.Success<StoredTemplate>>(result, result.toString())
                assertTrue(success.warnings.isEmpty())
            }
        } finally {
            executor.shutdownNow()
        }

        val snapshot = repositories.first().scan()
        val templateDirectories = snapshot.children
            .filterIsInstance<LibraryEntry.Template>()
            .map { it.directory.name }
        val storedOrder = requireNotNull(LibraryFolderOrderCodec.read(physicalRoot).value)
        assertEquals(24, templateDirectories.size)
        assertEquals(templateDirectories, storedOrder.templates)
    }

    @Test
    fun `converts directory iteration failures into repository failures`() {
        val ioFailure = IOException("directory changed during iteration")

        val result = protectRepositoryOperation<Unit>("read folder") {
            throw DirectoryIteratorException(ioFailure)
        }

        val failure = assertIs<RepositoryResult.Failure>(result)
        assertEquals("Unable to read folder: directory changed during iteration", failure.message)
        assertEquals(ioFailure, failure.cause)
    }

    @Test
    fun `returns a snapshot diagnostic when the library path is not a directory`() {
        val root = temporaryDirectory.resolve("library")
        Files.writeString(root, "file")

        val snapshot = FileSystemPromptTemplateRepository(root).scan()

        assertTrue(snapshot.children.isEmpty())
        assertTrue(snapshot.diagnostic.orEmpty().contains("not a regular directory"))
    }

    private fun hasQuarantine(parent: Path): Boolean = parent.useDirectoryEntries { entries ->
        entries.any { it.name.startsWith(FileSystemPromptTemplateRepository.DELETE_SCRATCH_PREFIX) }
    }

    private fun createJunction(link: Path, target: Path) {
        val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
    }

    private fun writeTemplate(directory: Path, name: String, id: TemplateId): Path {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("prompt.md"), "# $name")
        Files.writeString(
            directory.resolve("prompt.meta.json"),
            TemplateMetadataCodec().encode(metadata(name, id)),
        )
        return directory
    }

    private fun metadata(name: String, id: TemplateId): TemplateMetadata = TemplateMetadata(
        id = id.value,
        name = name,
    )

    private fun folder(snapshot: LibrarySnapshot, vararg names: String): LibraryEntry.Folder {
        var children = snapshot.children
        var current: LibraryEntry.Folder? = null
        names.forEach { name ->
            val folder = assertIs<LibraryEntry.Folder>(children.first { it.displayName == name })
            current = folder
            children = folder.children
        }
        return assertNotNull(current)
    }

    private fun <T> success(result: RepositoryResult<T>): T =
        assertIs<RepositoryResult.Success<T>>(result, result.toString()).value
}
