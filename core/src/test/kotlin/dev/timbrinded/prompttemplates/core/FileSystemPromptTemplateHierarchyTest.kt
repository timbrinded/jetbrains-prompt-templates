package dev.timbrinded.prompttemplates.core

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.useDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(Path.of("Reviews/Security/audit"), audit.relativeDirectory)
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
        Files.createDirectories(root.resolve("Visible"))
        val repository = FileSystemPromptTemplateRepository(root)

        assertEquals(listOf("Visible"), repository.scan().children.map(LibraryEntry::displayName))
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
        Files.createSymbolicLink(linkedMarkdown.resolve("prompt.md"), outside)

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

        val repository = FileSystemPromptTemplateRepository(root)
        assertEquals(listOf("Alpha", "Zulu"), repository.scan().children.map(LibraryEntry::displayName))

        Files.writeString(root.resolve(FileSystemPromptTemplateRepository.ORDER_FILE), "invalid")
        val invalidOrder = repository.scan()
        assertTrue(invalidOrder.diagnostic.orEmpty().contains("invalid"))
        assertEquals(listOf("Alpha", "Zulu"), invalidOrder.children.map(LibraryEntry::displayName))
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
    fun `falls back to alphabetical order for malformed or unsupported order data`() {
        val root = temporaryDirectory.resolve("library")
        Files.createDirectories(root.resolve("Zulu"))
        Files.createDirectories(root.resolve("Alpha"))
        val repository = FileSystemPromptTemplateRepository(root)
        val orderPath = root.resolve(FileSystemPromptTemplateRepository.ORDER_FILE)
        Files.writeString(orderPath, "not-json")

        val malformed = repository.scan()

        assertEquals(listOf("Alpha", "Zulu"), malformed.children.map(LibraryEntry::displayName))
        assertTrue(malformed.diagnostic.orEmpty().contains("invalid"))

        Files.writeString(orderPath, """{"schemaVersion": 2, "folders": ["Zulu"]}""")
        val unsupported = repository.scan()
        assertEquals(listOf("Alpha", "Zulu"), unsupported.children.map(LibraryEntry::displayName))
        assertTrue(unsupported.diagnostic.orEmpty().contains("Unsupported"))
    }

    @Test
    fun `falls back to alphabetical order when the order file is not valid UTF-8`() {
        val root = temporaryDirectory.resolve("library")
        Files.createDirectories(root.resolve("Zulu"))
        Files.createDirectories(root.resolve("Alpha"))
        Files.write(
            root.resolve(FileSystemPromptTemplateRepository.ORDER_FILE),
            byteArrayOf(0xC3.toByte()),
        )

        val snapshot = FileSystemPromptTemplateRepository(root).scan()

        assertEquals(listOf("Alpha", "Zulu"), snapshot.children.map(LibraryEntry::displayName))
        assertTrue(snapshot.diagnostic.orEmpty().startsWith("Unable to read"))
    }

    @Test
    fun `creates imports updates loads and exports templates in nested folders`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val reviews = success(repository.createFolder(root, "Reviews"))
        val security = success(repository.createFolder(reviews, "Security"))
        val id = TemplateId.random()
        val created = success(
            repository.create(
                PromptTemplateDraft(id = id, name = "Audit", markdown = "Review {{scope}}"),
                security,
            ),
        )
        val source = temporaryDirectory.resolve("source.md")
        Files.writeString(source, "# Imported\n\nUse {{value}}")
        val imported = success(repository.importMarkdown(source, security))

        val updated = success(
            repository.update(
                created.directory,
                PromptTemplateDraft(id = id, name = "Audit", markdown = "Updated"),
            ),
        )
        val destination = temporaryDirectory.resolve("export/audit.md")
        success(repository.exportTemplateMarkdown(updated.directory, destination))

        assertEquals("Updated", success(repository.load(created.directory)).template.markdown)
        assertEquals("Updated", destination.readText())
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
    fun `selects a two-step operation only for a case-only same-file rename`() {
        assertEquals(
            FolderRenameOperation.DIRECT,
            selectFolderRenameOperation(destinationExists = false, destinationRefersToSource = false),
        )
        assertEquals(
            FolderRenameOperation.CASE_ONLY_TWO_STEP,
            selectFolderRenameOperation(destinationExists = true, destinationRefersToSource = true),
        )
        assertEquals(
            FolderRenameOperation.COLLISION,
            selectFolderRenameOperation(destinationExists = true, destinationRefersToSource = false),
        )
    }

    @Test
    fun `renames folder casing and updates the stored order key after success`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val reviews = success(repository.createFolder(root, "reviews"))

        val renamed = success(repository.renameFolder(reviews, "Reviews"))

        assertEquals(root.resolve("Reviews"), renamed)
        assertEquals(listOf("Reviews"), repository.scan().children.map(LibraryEntry::displayName))
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
        Files.createSymbolicLink(stored.directory.resolve("outside-link"), outside)

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
    fun `fresh deletion never follows an intermediate replaced after fingerprinting`() {
        val target = temporaryDirectory.resolve("target")
        val intermediate = target.resolve("intermediate")
        val displaced = temporaryDirectory.resolve("displaced")
        val outside = temporaryDirectory.resolve("outside")
        Files.createDirectories(intermediate)
        Files.createDirectories(outside)
        Files.writeString(intermediate.resolve("victim.txt"), "original")
        Files.writeString(outside.resolve("victim.txt"), "outside")

        val fingerprint = LibraryTreeDeletion.manifest(target) { false }.fingerprint
        Files.move(intermediate, displaced)
        Files.createSymbolicLink(intermediate, outside)

        LibraryTreeDeletion.deleteTree(target, LibraryDeletionMode.CONSERVATIVE_FALLBACK)

        assertTrue(fingerprint.isNotBlank())
        assertFalse(Files.exists(target))
        assertEquals("outside", outside.resolve("victim.txt").readText())
        assertEquals("original", displaced.resolve("victim.txt").readText())
    }

    @Test
    fun `forced Windows-capable fallback deletes a nested tree without file keys`() {
        val target = temporaryDirectory.resolve("target")
        Files.createDirectories(target.resolve("one/two"))
        Files.writeString(target.resolve("root.txt"), "root")
        Files.writeString(target.resolve("one/child.txt"), "child")
        Files.writeString(target.resolve("one/two/deep.txt"), "deep")

        LibraryTreeDeletion.deleteTree(target, LibraryDeletionMode.CONSERVATIVE_FALLBACK)

        assertFalse(Files.exists(target))
        val hasQuarantine = temporaryDirectory.useDirectoryEntries { entries ->
            entries.any { it.name.startsWith(FileSystemPromptTemplateRepository.DELETE_SCRATCH_PREFIX) }
        }
        assertFalse(hasQuarantine)
    }

    @Test
    fun `deletes a nested template without following its support symlinks`() {
        val root = temporaryDirectory.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val folder = success(repository.createFolder(root, "Folder"))
        val stored = success(repository.create(PromptTemplateDraft(name = "Template", markdown = "body"), folder))
        val outside = temporaryDirectory.resolve("outside")
        Files.createDirectories(outside)
        Files.writeString(outside.resolve("keep.txt"), "keep")
        Files.createSymbolicLink(stored.directory.resolve("support"), outside)

        success(repository.deleteTemplate(stored.directory))

        assertFalse(Files.exists(stored.directory))
        assertEquals("keep", outside.resolve("keep.txt").readText())
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
        success(repository.createFolder(root, "Safe"))
        val outside = temporaryDirectory.resolve("outside")
        Files.createDirectories(outside)
        val link = root.resolve("Link")
        Files.createSymbolicLink(link, outside)

        assertIs<RepositoryResult.Failure>(repository.createFolder(link, "Escaped"))
        assertFalse(Files.exists(outside.resolve("Escaped")))
        assertIs<RepositoryResult.Failure>(repository.createFolder(root, "prompt.md"))
        assertIs<RepositoryResult.Failure>(repository.createFolder(root, "bad/name"))
    }

    @Test
    fun `supports an explicitly configured symlink library root`() {
        val physicalRoot = temporaryDirectory.resolve("physical-library")
        Files.createDirectories(physicalRoot)
        val linkedRoot = temporaryDirectory.resolve("linked-library")
        Files.createSymbolicLink(linkedRoot, physicalRoot)
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
        Files.createSymbolicLink(aliasParent, physicalParent)
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
