package dev.timbrinded.prompttemplates.core

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.useDirectoryEntries

internal inline fun <T> protectRepositoryOperation(
    operation: String,
    block: () -> RepositoryResult<T>,
): RepositoryResult<T> = try {
    block()
} catch (error: IOException) {
    RepositoryResult.Failure("Unable to $operation: ${error.message}", error)
} catch (error: DirectoryIteratorException) {
    val cause = error.cause ?: error
    RepositoryResult.Failure("Unable to $operation: ${cause.message}", cause)
} catch (error: IllegalArgumentException) {
    RepositoryResult.Failure(error.message ?: "Unable to $operation.", error)
} catch (error: SecurityException) {
    RepositoryResult.Failure("Unable to $operation: permission denied.", error)
}

class FileSystemPromptTemplateRepository internal constructor(
    val root: Path,
    private val codec: TemplateMetadataCodec,
    private val files: TemplateFileStore,
) {
    constructor(
        root: Path,
        codec: TemplateMetadataCodec = TemplateMetadataCodec(),
    ) : this(root, codec, TemplateFileStore(codec))

    private val paths = LibraryPaths(root)
    private val treeScanner = LibraryTreeScanner(root, codec, files::recover)
    private val orders = FolderOrderStore(treeScanner, paths)
    private val reconciler = TemplateReconciler()

    fun scan(): LibrarySnapshot {
        if (!Files.isDirectory(root)) return treeScanner.scan()
        return when (val result = protect("scan library") {
            LibraryFileLock.withLock(root) { RepositoryResult.Success(treeScanner.scan()) }
        }) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure ->
                LibrarySnapshot(paths.root, emptyList(), result.message, locked = result.cause is LibraryLockedException)
        }
    }

    fun load(directory: Path): RepositoryResult<StoredTemplate> = mutateLibrary("load template") {
        loadLocked(directory)
    }

    private fun loadLocked(directory: Path): RepositoryResult<StoredTemplate> = protect("load template") {
        val safeDirectory = paths.requireTemplateDirectory(directory)
        val contents = files.read(safeDirectory)
        val markdown = contents.markdown
            ?: return@protect RepositoryResult.Failure("Template is missing a regular $MARKDOWN_FILE file.")
        val metadata = contents.metadata
        if (metadata == null) {
            val inferred = inferredMetadata(safeDirectory, markdown)
            return@protect RepositoryResult.Success(
                StoredTemplate(PromptTemplate(inferred, markdown), safeDirectory, recoverable = true, revision = contents.revision),
            )
        }

        when (val decoded = codec.decode(metadata)) {
            is MetadataDecodeResult.Success -> RepositoryResult.Success(
                StoredTemplate(PromptTemplate(decoded.metadata, markdown), safeDirectory, revision = contents.revision),
            )

            is MetadataDecodeResult.Invalid -> RepositoryResult.Failure(decoded.message, decoded.cause)
            is MetadataDecodeResult.UnsupportedVersion -> RepositoryResult.Failure(
                "Metadata schema ${decoded.found} is newer than supported schema $CURRENT_SCHEMA_VERSION.",
            )
        }
    }

    fun create(
        draft: PromptTemplateDraft,
        destinationFolder: Path = root,
    ): RepositoryResult<StoredTemplate> = mutateLibrary("create template", createRoot = true) {
        val template = draft.toTemplate()
        codec.validate(template.metadata)?.let { return@mutateLibrary RepositoryResult.Failure(it) }
        val destination = paths.requireOrganiserFolder(destinationFolder, createRoot = true)
        duplicateVisibleName(destination, template.metadata.name)?.let {
            return@mutateLibrary RepositoryResult.Failure("An entry named '${template.metadata.name}' already exists in this folder.")
        }
        treeScanner.templateWithId(template.id)?.let {
            return@mutateLibrary RepositoryResult.Failure("Template UUID '${template.id.value}' already exists in the library.")
        }

        val previousOrder = orders.effective(destination)
        val directory = LibraryPaths.nextAvailableDirectory(destination, LibraryPaths.slug(template.metadata.name))
        Files.createDirectory(directory)
        val revision = try {
            files.save(directory, template, TemplateRevision.of(null, null))
        } catch (error: IOException) {
            // Before publishing intent there are no canonical files to recover. Never remove external additions.
            Files.newDirectoryStream(directory).use { if (!it.iterator().hasNext()) Files.delete(directory) }
            throw error
        }
        val updatedOrder = previousOrder.withNames(
            EntryKind.TEMPLATE,
            previousOrder.templates + directory.name,
        )
        val warnings = orders.persistAfterChange(destination, updatedOrder)
        RepositoryResult.Success(StoredTemplate(template, directory, revision = revision), warnings)
    }

    fun update(
        directory: Path,
        draft: PromptTemplateDraft,
        expectedRevision: TemplateRevision?,
    ): RepositoryResult<StoredTemplate> = mutateLibrary("update template") {
        val safeDirectory = paths.requireTemplateDirectory(directory)
        val template = draft.toTemplate()
        codec.validate(template.metadata)?.let { return@mutateLibrary RepositoryResult.Failure(it) }
        val stored = when (val result = loadLocked(safeDirectory)) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure -> return@mutateLibrary result
        }
        if (!stored.template.id.value.equals(template.id.value, ignoreCase = true)) {
            return@mutateLibrary RepositoryResult.Failure(
                "Refusing to overwrite a different template. Reload the library and try again.",
            )
        }
        if (stored.revision != expectedRevision) return@mutateLibrary RepositoryResult.Conflict(stored)
        duplicateVisibleName(safeDirectory.parent, template.metadata.name, excluding = safeDirectory)?.let {
            return@mutateLibrary RepositoryResult.Failure("An entry named '${template.metadata.name}' already exists in this folder.")
        }
        treeScanner.templateWithId(template.id, excluding = safeDirectory)?.let {
            return@mutateLibrary RepositoryResult.Failure("Template UUID '${template.id.value}' already exists in the library.")
        }
        val revision = try {
            files.save(safeDirectory, template, requireNotNull(stored.revision))
        } catch (_: TemplateRevisionMismatch) {
            return@mutateLibrary when (val latest = loadLocked(safeDirectory)) {
                is RepositoryResult.Success -> RepositoryResult.Conflict(latest.value)
                is RepositoryResult.Failure -> latest
            }
        }
        RepositoryResult.Success(StoredTemplate(template, safeDirectory, revision = revision))
    }

    /**
     * Deletes a template package that holds only template files. With [expectedId], the package must still
     * contain that template, so a stale view cannot delete a different template now at the same path.
     */
    fun deleteTemplate(directory: Path, expectedId: TemplateId? = null): RepositoryResult<Unit> = mutateLibrary("delete template") {
        val safeDirectory = paths.requireTemplateDirectory(directory)
        val unexpected = unexpectedPackageEntries(safeDirectory)
        if (unexpected.isNotEmpty()) {
            return@mutateLibrary RepositoryResult.Failure(
                "The template folder also contains ${quotedEntryNames(unexpected)}, which are not template files. " +
                    "Move or remove them in a file manager, then delete the template again.",
            )
        }
        if (expectedId != null && !currentTemplateId(safeDirectory)?.value.equals(expectedId.value, ignoreCase = true)) {
            return@mutateLibrary RepositoryResult.Failure(
                "The template changed on disk. Refresh the library and try again.",
            )
        }
        val parent = safeDirectory.parent
        val previousOrder = orders.effective(parent)
        LibraryTreeDeletion.deleteTree(safeDirectory)
        val updated = previousOrder.removing(safeDirectory.name, EntryKind.TEMPLATE)
        RepositoryResult.Success(Unit, orders.persistAfterChange(parent, updated))
    }

    fun importMarkdown(
        source: Path,
        destinationFolder: Path = root,
    ): RepositoryResult<StoredTemplate> = protect("import Markdown") {
        if (!Files.isRegularFile(source, NOFOLLOW_LINKS) || source.extension.lowercase() != "md") {
            return@protect RepositoryResult.Failure("Select a regular Markdown (.md) file.")
        }
        val markdown = Files.readString(source, Charsets.UTF_8)
        val inferredName = firstHeading(markdown) ?: source.nameWithoutExtension
        create(
            PromptTemplateDraft(
                name = inferredName,
                variables = inferredVariables(markdown),
                markdown = markdown,
            ),
            destinationFolder,
        )
    }

    fun exportTemplateMarkdown(
        directory: Path,
        destination: Path,
    ): RepositoryResult<Path> = mutateLibrary("export template Markdown") {
        when (val loaded = loadLocked(directory)) {
            is RepositoryResult.Failure -> loaded
            is RepositoryResult.Success -> {
                ensureDestinationParent(destination)
                writeTextAtomically(destination, loaded.value.template.markdown, allowNonAtomicMove = true)
                RepositoryResult.Success(destination)
            }
        }
    }

    fun exportRenderedMarkdown(
        rendered: String,
        destination: Path,
    ): RepositoryResult<Path> = protect("export rendered Markdown") {
        ensureDestinationParent(destination)
        writeTextAtomically(destination, rendered, allowNonAtomicMove = true)
        RepositoryResult.Success(destination)
    }

    fun createFolder(parent: Path, name: String): RepositoryResult<Path> = mutateLibrary("create folder", createRoot = true) {
        val safeParent = paths.requireOrganiserFolder(parent, createRoot = true)
        val validName = LibraryPaths.requireFolderName(name)
        duplicateVisibleName(safeParent, validName)?.let {
            return@mutateLibrary RepositoryResult.Failure("An entry named '$validName' already exists in this folder.")
        }
        val directory = safeParent.resolve(validName)
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            return@mutateLibrary RepositoryResult.Failure("A filesystem entry named '$validName' already exists.")
        }
        val previousOrder = orders.effective(safeParent)
        Files.createDirectory(directory)
        val updatedOrder = previousOrder.withNames(EntryKind.FOLDER, previousOrder.folders + validName)
        val warnings = orders.persistAfterChange(safeParent, updatedOrder)
        RepositoryResult.Success(directory, warnings)
    }

    fun renameFolder(directory: Path, newName: String): RepositoryResult<Path> =
        mutateLibrary("rename folder") {
        val safeDirectory = paths.requireOrganiserFolder(directory)
        require(safeDirectory != paths.root) { "The library root cannot be renamed." }
        val validName = LibraryPaths.requireFolderName(newName)
        if (safeDirectory.name == validName) {
            return@mutateLibrary RepositoryResult.Success(safeDirectory)
        }
        val parent = safeDirectory.parent
        duplicateVisibleName(parent, validName, excluding = safeDirectory)?.let {
            return@mutateLibrary RepositoryResult.Failure("An entry named '$validName' already exists in this folder.")
        }
        val destination = parent.resolve(validName)
        val destinationExists = Files.exists(destination, NOFOLLOW_LINKS)
        if (destinationExists && !Files.isSameFile(safeDirectory, destination)) {
            return@mutateLibrary RepositoryResult.Failure(
                "A filesystem entry named '$validName' already exists.",
            )
        }

        val previousOrder = orders.effective(parent)
        if (destinationExists) {
            LibraryMoves.moveCaseOnly(safeDirectory, destination)
        } else {
            LibraryMoves.moveWithoutReplacement(safeDirectory, destination)
        }
        val updatedOrder = previousOrder.replacing(
            safeDirectory.name,
            validName,
            EntryKind.FOLDER,
        )
        RepositoryResult.Success(destination, orders.persistAfterChange(parent, updatedOrder))
    }

    fun moveEntry(
        entry: Path,
        destinationFolder: Path,
        placement: EntryPlacement = EntryPlacement.EndOfKind,
    ): RepositoryResult<Path> = mutateLibrary("move library entry") {
        val safeEntry = paths.requireEntry(entry)
        val safeDestination = paths.requireOrganiserFolder(destinationFolder)
        val directEntry = treeScanner.classify(safeEntry)
        val kind = directEntry.kind
        if (kind == EntryKind.FOLDER &&
            (safeDestination == safeEntry || safeDestination.startsWith(safeEntry))
        ) {
            return@mutateLibrary RepositoryResult.Failure("A folder cannot be moved into itself or one of its descendants.")
        }

        val sourceParent = safeEntry.parent
        val sameParent = sourceParent == safeDestination
        duplicateVisibleName(safeDestination, directEntry.visibleName, excluding = if (sameParent) safeEntry else null)?.let {
            return@mutateLibrary RepositoryResult.Failure(
                "An entry named '${directEntry.visibleName}' already exists in the destination folder.",
            )
        }
        val target = when {
            sameParent -> safeEntry
            // A template's directory name is only a slug, so a hidden collision just needs another free name.
            kind == EntryKind.TEMPLATE -> LibraryPaths.nextAvailableDirectory(safeDestination, safeEntry.name)
            else -> safeDestination.resolve(safeEntry.name)
        }
        if (!sameParent && Files.exists(target, NOFOLLOW_LINKS)) {
            return@mutateLibrary RepositoryResult.Failure(
                "A filesystem entry named '${safeEntry.fileName}' already exists in the destination folder.",
            )
        }

        val sourceOrder = orders.effective(sourceParent)
        val destinationOrder = if (sameParent) sourceOrder else orders.effective(safeDestination)
        val placedDestinationOrder = orders.placing(
            destinationOrder,
            target.name,
            kind,
            placement,
            safeDestination,
            source = safeEntry,
        )

        val resultPath = if (sameParent) {
            safeEntry
        } else {
            if (Files.getFileStore(safeEntry) != Files.getFileStore(safeDestination)) {
                return@mutateLibrary RepositoryResult.Failure(
                    "Entries cannot be moved between filesystems. No files were changed.",
                )
            }
            LibraryMoves.moveWithoutReplacement(safeEntry, target)
            target
        }

        val warnings = if (sameParent) {
            orders.persist(sourceParent, placedDestinationOrder)
            emptyList()
        } else {
            buildList {
                addAll(orders.persistAfterChange(sourceParent, sourceOrder.removing(safeEntry.name, kind)))
                addAll(orders.persistAfterChange(safeDestination, placedDestinationOrder))
            }
        }
        RepositoryResult.Success(resultPath, warnings)
    }

    fun previewFolderDeletion(directory: Path): RepositoryResult<FolderDeletionPreview> =
        protect("inspect folder") {
            val safeDirectory = paths.requireOrganiserFolder(directory)
            require(safeDirectory != paths.root) { "The library root cannot be deleted." }
            RepositoryResult.Success(LibraryTreeDeletion.manifest(safeDirectory))
        }

    fun deleteFolder(preview: FolderDeletionPreview): RepositoryResult<Unit> = mutateLibrary("delete folder") {
        val safeDirectory = paths.requireOrganiserFolder(preview.directory)
        require(safeDirectory != paths.root) { "The library root cannot be deleted." }
        val current = LibraryTreeDeletion.manifest(safeDirectory)
        if (current != preview.copy(directory = safeDirectory)) {
            return@mutateLibrary RepositoryResult.Failure(
                "The folder contents changed after confirmation. Review the folder and confirm deletion again.",
            )
        }

        val parent = safeDirectory.parent
        val previousOrder = orders.effective(parent)
        LibraryTreeDeletion.deleteTree(safeDirectory)
        val updated = previousOrder.removing(safeDirectory.name, EntryKind.FOLDER)
        RepositoryResult.Success(Unit, orders.persistAfterChange(parent, updated))
    }

    private fun unexpectedPackageEntries(directory: Path): List<String> = directory.useDirectoryEntries { entries ->
        entries
            .filter { Files.isDirectory(it, NOFOLLOW_LINKS) || !LibraryLayout.isTemplatePackageFileName(it.name) }
            .map { it.name }
            .toList()
    }

    /** The identity [loadLocked] would report, or null when the package cannot be read as a template. */
    private fun currentTemplateId(directory: Path): TemplateId? {
        val contents = try {
            files.read(directory)
        } catch (_: IOException) {
            return null
        }
        val metadata = contents.metadata ?: return contents.markdown?.let { TemplateId(inferredId(directory)) }
        val decoded = codec.decode(metadata) as? MetadataDecodeResult.Success ?: return null
        return TemplateId(decoded.metadata.id)
    }

    private fun inferredId(directory: Path): String =
        UUID.nameUUIDFromBytes(directory.toAbsolutePath().normalize().toString().encodeToByteArray()).toString()

    private fun inferredMetadata(directory: Path, markdown: String): TemplateMetadata = TemplateMetadata(
        id = inferredId(directory),
        name = firstHeading(markdown) ?: directory.name,
        variables = inferredVariables(markdown),
    )

    private fun inferredVariables(markdown: String): List<PromptVariable> =
        reconciler.reconcile(markdown, existing = emptyList()).variables

    private fun duplicateVisibleName(
        parent: Path,
        name: String,
        excluding: Path? = null,
    ): DirectLibraryEntry? {
        val excluded = excluding?.toAbsolutePath()?.normalize()
        return treeScanner.directEntries(parent).firstOrNull { candidate ->
            candidate.path != excluded && candidate.visibleName.trim().equals(name.trim(), ignoreCase = true)
        }
    }

    private fun ensureDestinationParent(destination: Path) {
        destination.parent?.let(Files::createDirectories)
    }

    private fun firstHeading(markdown: String): String? = markdown.lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("# ") }
        ?.removePrefix("# ")
        ?.trim()
        ?.ifEmpty { null }

    private inline fun <T> protect(operation: String, block: () -> RepositoryResult<T>): RepositoryResult<T> =
        protectRepositoryOperation(operation, block)

    private fun <T> mutateLibrary(
        operation: String,
        createRoot: Boolean = false,
        block: () -> RepositoryResult<T>,
    ): RepositoryResult<T> = protect(operation) {
        val directory = if (createRoot) paths.ensureRoot() else paths.requireRoot()
        LibraryFileLock.withLock(directory, block = block)
    }

    companion object {
        const val MARKDOWN_FILE = LibraryLayout.MARKDOWN_FILE
        const val METADATA_FILE = LibraryLayout.METADATA_FILE
        const val ORDER_FILE = LibraryLayout.ORDER_FILE
        const val SAVE_JOURNAL_FILE = LibraryLayout.SAVE_JOURNAL_FILE
        const val DELETE_SCRATCH_PREFIX = LibraryLayout.DELETE_SCRATCH_PREFIX
        const val RENAME_SCRATCH_PREFIX = LibraryLayout.RENAME_SCRATCH_PREFIX

        /** Entries the library never shows or manages: version-control metadata and the repository's own working files. */
        fun isInternalLibraryEntryName(name: String): Boolean = LibraryLayout.isInternalLibraryEntryName(name)

        /**
         * Whether a traversal below the library root must treat [path] as a link and not descend into it. This
         * covers Windows directory junctions, which report as directories that are not symbolic links.
         */
        fun isLinkEntry(path: Path, attributes: BasicFileAttributes): Boolean = LibraryLayout.isLink(path, attributes)
    }
}
