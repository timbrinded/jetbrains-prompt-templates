package dev.timbrinded.prompttemplates.core

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.useDirectoryEntries
import kotlin.uuid.Uuid

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
    override val root: Path,
    private val codec: TemplateMetadataCodec,
    private val parser: PlaceholderParser,
    private val files: TemplateFileStore,
) : PromptTemplateRepository {
    constructor(
        root: Path,
        codec: TemplateMetadataCodec = TemplateMetadataCodec(),
        parser: PlaceholderParser = LinearPlaceholderParser(),
    ) : this(root, codec, parser, TemplateFileStore(codec))

    private val treeScanner = LibraryTreeScanner(root, codec, files::recover)

    override fun scan(): LibrarySnapshot {
        if (!Files.isDirectory(root)) return treeScanner.scan()
        return when (val result = protect("scan library") {
            LibraryFileLock.withLock(root) { RepositoryResult.Success(treeScanner.scan()) }
        }) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure -> LibrarySnapshot(normalizedRoot(), emptyList(), result.message)
        }
    }

    override fun load(directory: Path): RepositoryResult<StoredTemplate> = mutateLibrary("load template") {
        loadLocked(directory)
    }

    private fun loadLocked(directory: Path): RepositoryResult<StoredTemplate> = protect("load template") {
        val safeDirectory = requireTemplateDirectory(directory)
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

    override fun create(
        draft: PromptTemplateDraft,
        destinationFolder: Path,
    ): RepositoryResult<StoredTemplate> = mutateLibrary("create template", createRoot = true) {
        val template = draft.toTemplate()
        codec.validate(template.metadata)?.let { return@mutateLibrary RepositoryResult.Failure(it) }
        val destination = requireOrganiserFolder(destinationFolder, createRoot = true)
        duplicateVisibleName(destination, template.metadata.name)?.let {
            return@mutateLibrary RepositoryResult.Failure("An entry named '${template.metadata.name}' already exists in this folder.")
        }
        treeScanner.templateWithId(template.id)?.let {
            return@mutateLibrary RepositoryResult.Failure("Template UUID '${template.id.value}' already exists in the library.")
        }

        val previousOrder = effectiveOrder(destination)
        val directory = nextAvailableDirectory(destination, slugify(template.metadata.name))
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
        val warnings = persistOrderWarnings(destination, updatedOrder)
        RepositoryResult.Success(StoredTemplate(template, directory, revision = revision), warnings)
    }

    override fun update(
        directory: Path,
        draft: PromptTemplateDraft,
        expectedRevision: TemplateRevision?,
    ): RepositoryResult<StoredTemplate> = mutateLibrary("update template") {
        val safeDirectory = requireTemplateDirectory(directory)
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

    override fun deleteTemplate(directory: Path, expectedId: TemplateId?): RepositoryResult<Unit> = mutateLibrary("delete template") {
        val safeDirectory = requireTemplateDirectory(directory)
        val unexpected = unexpectedPackageEntries(safeDirectory)
        if (unexpected.isNotEmpty()) {
            val shown = unexpected.take(3).joinToString(", ") { "'$it'" }
            val more = if (unexpected.size > 3) " and ${unexpected.size - 3} more" else ""
            return@mutateLibrary RepositoryResult.Failure(
                "The template folder also contains $shown$more, which are not template files. " +
                    "Move or remove them in a file manager, then delete the template again.",
            )
        }
        if (expectedId != null && !currentTemplateId(safeDirectory)?.value.equals(expectedId.value, ignoreCase = true)) {
            return@mutateLibrary RepositoryResult.Failure(
                "The template changed on disk. Refresh the library and try again.",
            )
        }
        val parent = safeDirectory.parent
        val previousOrder = effectiveOrder(parent)
        LibraryTreeDeletion.deleteTree(safeDirectory)
        val updated = previousOrder.removing(safeDirectory.name, EntryKind.TEMPLATE)
        RepositoryResult.Success(Unit, persistOrderWarnings(parent, updated))
    }

    override fun importMarkdown(
        source: Path,
        destinationFolder: Path,
    ): RepositoryResult<StoredTemplate> = protect("import Markdown") {
        if (!Files.isRegularFile(source, NOFOLLOW_LINKS) || source.extension.lowercase() != "md") {
            return@protect RepositoryResult.Failure("Select a regular Markdown (.md) file.")
        }
        val markdown = Files.readString(source, Charsets.UTF_8)
        val inferredName = firstHeading(markdown) ?: source.nameWithoutExtension
        val variables = parser.parse(markdown).placeholders
            .filterNot(PlaceholderToken::contextReference)
            .map(PlaceholderToken::key)
            .distinct()
            .map { key -> PromptVariable(key = key, label = defaultVariableLabel(key)) }
        create(
            PromptTemplateDraft(
                name = inferredName,
                variables = variables,
                markdown = markdown,
            ),
            destinationFolder,
        )
    }

    override fun exportTemplateMarkdown(
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

    override fun exportRenderedMarkdown(
        rendered: String,
        destination: Path,
    ): RepositoryResult<Path> = protect("export rendered Markdown") {
        ensureDestinationParent(destination)
        writeTextAtomically(destination, rendered, allowNonAtomicMove = true)
        RepositoryResult.Success(destination)
    }

    override fun createFolder(parent: Path, name: String): RepositoryResult<Path> = mutateLibrary("create folder", createRoot = true) {
        val safeParent = requireOrganiserFolder(parent, createRoot = true)
        val validName = requireFolderName(name)
        duplicateVisibleName(safeParent, validName)?.let {
            return@mutateLibrary RepositoryResult.Failure("An entry named '$validName' already exists in this folder.")
        }
        val directory = safeParent.resolve(validName)
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            return@mutateLibrary RepositoryResult.Failure("A filesystem entry named '$validName' already exists.")
        }
        val previousOrder = effectiveOrder(safeParent)
        Files.createDirectory(directory)
        val updatedOrder = previousOrder.withNames(EntryKind.FOLDER, previousOrder.folders + validName)
        val warnings = persistOrderWarnings(safeParent, updatedOrder)
        RepositoryResult.Success(directory, warnings)
    }

    override fun renameFolder(directory: Path, newName: String): RepositoryResult<Path> =
        mutateLibrary("rename folder") {
        val safeDirectory = requireOrganiserFolder(directory)
        require(safeDirectory != normalizedRoot()) { "The library root cannot be renamed." }
        val validName = requireFolderName(newName)
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

        val previousOrder = effectiveOrder(parent)
        if (destinationExists) {
            moveCaseOnlyFolder(safeDirectory, destination)
        } else {
            moveWithoutReplacement(safeDirectory, destination)
        }
        val updatedOrder = previousOrder.replacing(
            safeDirectory.name,
            validName,
            EntryKind.FOLDER,
        )
        RepositoryResult.Success(destination, persistOrderWarnings(parent, updatedOrder))
    }

    override fun moveEntry(
        entry: Path,
        destinationFolder: Path,
        placement: EntryPlacement,
    ): RepositoryResult<Path> = mutateLibrary("move library entry") {
        val safeEntry = requireLibraryEntry(entry)
        val safeDestination = requireOrganiserFolder(destinationFolder)
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
            kind == EntryKind.TEMPLATE -> nextAvailableDirectory(safeDestination, safeEntry.name)
            else -> safeDestination.resolve(safeEntry.name)
        }
        if (!sameParent && Files.exists(target, NOFOLLOW_LINKS)) {
            return@mutateLibrary RepositoryResult.Failure(
                "A filesystem entry named '${safeEntry.fileName}' already exists in the destination folder.",
            )
        }

        val sourceOrder = effectiveOrder(sourceParent)
        val destinationOrder = if (sameParent) sourceOrder else effectiveOrder(safeDestination)
        val placedDestinationOrder = destinationOrder.placing(
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
            moveWithoutReplacement(safeEntry, target)
            target
        }

        val warnings = if (sameParent) {
            persistOrder(sourceParent, placedDestinationOrder)
            emptyList()
        } else {
            buildList {
                addAll(persistOrderWarnings(sourceParent, sourceOrder.removing(safeEntry.name, kind)))
                addAll(persistOrderWarnings(safeDestination, placedDestinationOrder))
            }
        }
        RepositoryResult.Success(resultPath, warnings)
    }

    override fun previewFolderDeletion(directory: Path): RepositoryResult<FolderDeletionPreview> =
        protect("inspect folder") {
            val safeDirectory = requireOrganiserFolder(directory)
            require(safeDirectory != normalizedRoot()) { "The library root cannot be deleted." }
            RepositoryResult.Success(LibraryTreeDeletion.manifest(safeDirectory, treeScanner::isTemplatePackage))
        }

    override fun deleteFolder(preview: FolderDeletionPreview): RepositoryResult<Unit> = mutateLibrary("delete folder") {
        val safeDirectory = requireOrganiserFolder(preview.directory)
        require(safeDirectory != normalizedRoot()) { "The library root cannot be deleted." }
        val current = LibraryTreeDeletion.manifest(safeDirectory, treeScanner::isTemplatePackage)
        if (current != preview.copy(directory = safeDirectory)) {
            return@mutateLibrary RepositoryResult.Failure(
                "The folder contents changed after confirmation. Review the folder and confirm deletion again.",
            )
        }

        val parent = safeDirectory.parent
        val previousOrder = effectiveOrder(parent)
        LibraryTreeDeletion.deleteTree(safeDirectory)
        val updated = previousOrder.removing(safeDirectory.name, EntryKind.FOLDER)
        RepositoryResult.Success(Unit, persistOrderWarnings(parent, updated))
    }

    private fun unexpectedPackageEntries(directory: Path): List<String> = directory.useDirectoryEntries { entries ->
        entries
            .filter { Files.isDirectory(it, NOFOLLOW_LINKS) || !LibraryLayout.isTemplatePackageFileName(it.name) }
            .map { it.name }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
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

    private fun inferredMetadata(directory: Path, markdown: String): TemplateMetadata {
        val id = inferredId(directory)
        val variables = parser.parse(markdown).placeholders
            .filterNot(PlaceholderToken::contextReference)
            .map(PlaceholderToken::key)
            .distinct()
            .map { key -> PromptVariable(key = key, label = defaultVariableLabel(key)) }
        return TemplateMetadata(
            id = id,
            name = firstHeading(markdown) ?: directory.name,
            variables = variables,
        )
    }

    private fun requireTemplateDirectory(directory: Path): Path {
        val safeDirectory = requireExistingManagedDirectory(directory, allowRoot = false)
        require(treeScanner.isTemplatePackage(safeDirectory)) {
            "The selected entry is an organiser folder, not a template."
        }
        return safeDirectory
    }

    private fun requireOrganiserFolder(directory: Path, createRoot: Boolean = false): Path {
        val libraryRoot = if (createRoot) ensureRootDirectory() else requireLibraryRoot()
        val normalDirectory = directory.toAbsolutePath().normalize()
        require(normalDirectory == libraryRoot || normalDirectory.startsWith(libraryRoot)) {
            "Folder must be inside the template library."
        }
        val safeDirectory = requireExistingManagedDirectory(normalDirectory, allowRoot = true)
        if (safeDirectory != libraryRoot) {
            require(!treeScanner.isTemplatePackage(safeDirectory)) { "Templates cannot contain organiser folders." }
        }
        return safeDirectory
    }

    private fun requireLibraryEntry(entry: Path): Path =
        requireExistingManagedDirectory(entry, allowRoot = false)

    private fun requireExistingManagedDirectory(path: Path, allowRoot: Boolean): Path {
        val libraryRoot = requireLibraryRoot()
        val normalPath = path.toAbsolutePath().normalize()
        require(normalPath.startsWith(libraryRoot) && (allowRoot || normalPath != libraryRoot)) {
            "Entry must be inside the template library."
        }
        require(Files.exists(normalPath, NOFOLLOW_LINKS)) { "Library entry does not exist." }
        require(Files.isDirectory(normalPath)) { "Library entry is not a directory." }

        var current = libraryRoot
        libraryRoot.relativize(normalPath).forEach { segment ->
            val segmentName = segment.name
            if (segmentName.isEmpty()) return@forEach
            require(!isInternalLibraryEntryName(segmentName)) {
                "IDE metadata, version-control and library working directories are not part of the template library."
            }
            current = current.resolve(segment)
            require(!LibraryLayout.isLink(current)) { "Symbolic links and directory junctions are not supported." }
            require(Files.isDirectory(current, NOFOLLOW_LINKS)) { "Library path is not a directory." }
            require(current == normalPath || !treeScanner.isTemplatePackage(current)) {
                "Entries inside a template package are not part of the managed library hierarchy."
            }
        }
        val realRoot = libraryRoot.toRealPath()
        val realPath = normalPath.toRealPath()
        require(realPath.startsWith(realRoot)) { "Entry resolves outside the template library." }
        return normalPath
    }

    private fun requireLibraryRoot(): Path {
        val libraryRoot = normalizedRoot()
        require(Files.exists(libraryRoot, NOFOLLOW_LINKS)) { "The template library does not exist." }
        require(Files.isDirectory(libraryRoot)) { "The template library path is not a directory." }
        libraryRoot.toRealPath()
        return libraryRoot
    }

    private fun ensureRootDirectory(): Path {
        val libraryRoot = normalizedRoot()
        if (!Files.exists(libraryRoot, NOFOLLOW_LINKS)) Files.createDirectories(libraryRoot)
        return requireLibraryRoot()
    }

    private fun normalizedRoot(): Path = root.toAbsolutePath().normalize()

    private fun nextAvailableDirectory(parent: Path, base: String): Path {
        var candidate = parent.resolve(base)
        var suffix = 2
        while (Files.exists(candidate, NOFOLLOW_LINKS)) {
            candidate = parent.resolve("$base-$suffix")
            suffix++
        }
        return candidate
    }

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

    private fun requireFolderName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Folder name is required." }
        require(trimmed == name) { "Folder names cannot start or end with whitespace." }
        require(trimmed != "." && trimmed != "..") { "Folder name is not valid." }
        require(trimmed.none { it.code < 32 || it in INVALID_FOLDER_NAME_CHARACTERS }) {
            "Folder name contains a character that is not portable across supported systems."
        }
        require(!trimmed.endsWith('.')) { "Folder names cannot end with a period." }
        require(trimmed.encodeToByteArray().size <= MAX_NAME_BYTES) { "Folder name is too long." }
        require(!isWindowsDeviceName(trimmed)) { "'$trimmed' is a reserved device name on Windows." }
        require(
            trimmed.lowercase() !in RESERVED_ENTRY_NAMES,
        ) { "'$trimmed' is reserved by the prompt-template library." }
        require(!isLibraryManagementDirectoryName(trimmed)) {
            "'$trimmed' is reserved for IDE or version-control metadata."
        }
        require(!isLibraryScratchDirectoryName(trimmed)) {
            "'$trimmed' uses a prefix reserved for the library's working directories."
        }
        return trimmed
    }

    private fun effectiveOrder(folder: Path): FolderOrderState {
        val entries = treeScanner.directEntries(folder)
        val read = LibraryFolderOrderCodec.read(folder)
        val sorted = sortDirectEntries(entries, read.value)
        return FolderOrderState(
            folders = sorted.filter { it.kind == EntryKind.FOLDER }.map { it.path.name },
            templates = sorted.filter { it.kind == EntryKind.TEMPLATE }.map { it.path.name },
            unreadable = read.diagnostic,
        )
    }

    private fun persistOrder(folder: Path, order: FolderOrderState) {
        // A malformed or newer-schema order file may hold order this version cannot represent.
        order.unreadable?.let { throw IOException("$it The existing order file was left unchanged.") }
        val encoded = LibraryFolderOrderCodec.encode(order)
        writeTextAtomically(folder.resolve(ORDER_FILE), encoded, allowNonAtomicMove = true)
    }

    private fun persistOrderWarnings(folder: Path, order: FolderOrderState): List<String> = try {
        persistOrder(folder, order)
        emptyList()
    } catch (error: IOException) {
        listOf("The library change succeeded, but folder order could not be saved: ${error.message}")
    } catch (error: SecurityException) {
        listOf("The library change succeeded, but folder order could not be saved: permission denied.")
    }

    private fun sortDirectEntries(
        entries: List<DirectLibraryEntry>,
        order: FolderOrderFile?,
    ): List<DirectLibraryEntry> =
        entries.sortedWith(
            LibraryFolderOrderCodec.comparator(
                order = order,
                kindOf = DirectLibraryEntry::kind,
                orderKeyOf = { it.path.name },
                fallbackNameOf = DirectLibraryEntry::visibleName,
            ),
        )

    private fun FolderOrderState.placing(
        name: String,
        kind: EntryKind,
        placement: EntryPlacement,
        destinationFolder: Path,
        source: Path,
    ): FolderOrderState {
        val names = names(kind).toMutableList().also { it.remove(name) }
        val index = placementIndex(placement, destinationFolder, source, kind, names)
        names.add(index, name)
        return withNames(kind, names)
    }

    private fun placementIndex(
        placement: EntryPlacement,
        destinationFolder: Path,
        source: Path,
        kind: EntryKind,
        names: List<String>,
    ): Int {
        val sibling = when (placement) {
            EntryPlacement.EndOfKind -> return names.size
            is EntryPlacement.Before -> placement.sibling
            is EntryPlacement.After -> placement.sibling
        }
        val safeSibling = requireLibraryEntry(sibling)
        require(safeSibling.parent == destinationFolder) { "The placement target is not in the destination folder." }
        require(safeSibling != source) { "An entry cannot be placed relative to itself." }
        require(treeScanner.classify(safeSibling).kind == kind) { "Folders and templates cannot be interleaved." }
        val siblingIndex = names.indexOf(safeSibling.name)
        require(siblingIndex >= 0) { "The placement target is no longer available." }
        return siblingIndex + if (placement is EntryPlacement.After) 1 else 0
    }

    private fun moveWithoutReplacement(source: Path, destination: Path): Path {
        try {
            return Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            return Files.move(source, destination)
        }
    }

    private fun moveCaseOnlyFolder(source: Path, destination: Path) {
        val temporary = nextCaseRenameTemporaryPath(source.parent)
        moveWithoutReplacement(source, temporary)
        try {
            moveWithoutReplacement(temporary, destination)
        } catch (error: IOException) {
            throw rollbackCaseOnlyRename(temporary, source, error)
        } catch (error: SecurityException) {
            throw rollbackCaseOnlyRename(
                temporary,
                source,
                IOException("Permission was denied while applying the requested folder-name casing.", error),
            )
        }
    }

    private fun nextCaseRenameTemporaryPath(parent: Path): Path {
        while (true) {
            val candidate = parent.resolve("$RENAME_SCRATCH_PREFIX${Uuid.random()}")
            if (!Files.exists(candidate, NOFOLLOW_LINKS)) return candidate
        }
    }

    private fun rollbackCaseOnlyRename(
        temporary: Path,
        source: Path,
        renameError: IOException,
    ): IOException {
        if (!Files.exists(temporary, NOFOLLOW_LINKS) || Files.exists(source, NOFOLLOW_LINKS)) {
            return IOException(
                "Unable to apply the requested folder-name casing. ${retainedScratchFolderHint(temporary)}",
                renameError,
            )
        }
        return try {
            moveWithoutReplacement(temporary, source)
            renameError
        } catch (rollbackError: IOException) {
            IOException(
                "Unable to apply the requested folder-name casing or restore the original name. " +
                    retainedScratchFolderHint(temporary),
                renameError,
            ).apply { addSuppressed(rollbackError) }
        } catch (rollbackError: SecurityException) {
            IOException(
                "Unable to apply the requested folder-name casing or restore the original name. " +
                    retainedScratchFolderHint(temporary),
                renameError,
            ).apply { addSuppressed(rollbackError) }
        }
    }

    private fun retainedScratchFolderHint(temporary: Path): String =
        "The folder remains at '$temporary'. It is hidden from the library; rename it in a file manager to restore it."

    private fun ensureDestinationParent(destination: Path) {
        destination.parent?.let(Files::createDirectories)
    }

    private fun slugify(name: String): String {
        val slug = name
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .take(MAX_SLUG_LENGTH)
            .trim('-')
            .ifEmpty { "prompt-template" }
        return if (isWindowsDeviceName(slug)) "$slug-template" else slug
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
        val directory = if (createRoot) ensureRootDirectory() else requireLibraryRoot()
        LibraryFileLock.withLock(directory, block = block)
    }

    companion object {
        const val MARKDOWN_FILE = "prompt.md"
        const val METADATA_FILE = "prompt.meta.json"
        const val ORDER_FILE = LIBRARY_ORDER_FILE
        const val SAVE_JOURNAL_FILE = TemplateFileStore.JOURNAL_FILE

        private val RESERVED_ENTRY_NAMES = setOf(MARKDOWN_FILE, METADATA_FILE, ORDER_FILE, SAVE_JOURNAL_FILE, LibraryFileLock.FILE_NAME)
            .mapTo(mutableSetOf(), String::lowercase)
        private val LIBRARY_MANAGEMENT_DIRECTORY_NAMES = setOf(".git", ".hg", ".svn", ".idea")
        private val INVALID_FOLDER_NAME_CHARACTERS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        private val WINDOWS_DEVICE_NAMES = setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }
        private const val MAX_NAME_BYTES = 255

        /** Generated directory names stay short so nested paths remain well inside platform limits. */
        private const val MAX_SLUG_LENGTH = 64

        /** Windows reserves these stems with any extension, such as `NUL.txt`. */
        private fun isWindowsDeviceName(name: String): Boolean =
            name.substringBefore('.').trimEnd(' ').lowercase() in WINDOWS_DEVICE_NAMES

        /** Prefixes of the working directories the repository creates beside an entry it is deleting or renaming. */
        const val DELETE_SCRATCH_PREFIX = ".prompt-template-delete-"
        const val RENAME_SCRATCH_PREFIX = ".prompt-template-rename-"

        fun isLibraryManagementDirectoryName(name: String): Boolean =
            name.lowercase() in LIBRARY_MANAGEMENT_DIRECTORY_NAMES

        fun isLibraryScratchDirectoryName(name: String): Boolean =
            name.startsWith(DELETE_SCRATCH_PREFIX, ignoreCase = true) ||
                name.startsWith(RENAME_SCRATCH_PREFIX, ignoreCase = true) ||
                name.startsWith(TemplateFileStore.STAGE_PREFIX, ignoreCase = true)

        /** Entries the library never shows or manages: version-control metadata and the repository's own scratch directories. */
        fun isInternalLibraryEntryName(name: String): Boolean =
            isLibraryManagementDirectoryName(name) || isLibraryScratchDirectoryName(name) ||
                name.equals(LibraryFileLock.FILE_NAME, ignoreCase = true)
    }
}
