package dev.timbrinded.prompttemplates.core

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.concurrent.withLock

internal enum class FolderRenameOperation {
    DIRECT,
    CASE_ONLY_TWO_STEP,
    COLLISION,
}

internal fun selectFolderRenameOperation(
    destinationExists: Boolean,
    destinationRefersToSource: Boolean,
): FolderRenameOperation = when {
    !destinationExists -> FolderRenameOperation.DIRECT
    destinationRefersToSource -> FolderRenameOperation.CASE_ONLY_TWO_STEP
    else -> FolderRenameOperation.COLLISION
}

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

class FileSystemPromptTemplateRepository(
    override val root: Path,
    private val codec: TemplateMetadataCodec = TemplateMetadataCodec(),
    private val parser: PlaceholderParser = LinearPlaceholderParser(),
) : PromptTemplateRepository {
    override fun scan(): LibrarySnapshot {
        val libraryRoot = normalizedRoot()
        if (!Files.exists(libraryRoot, NOFOLLOW_LINKS)) {
            return LibrarySnapshot(libraryRoot, emptyList())
        }
        if (!Files.isDirectory(libraryRoot)) {
            return LibrarySnapshot(
                root = libraryRoot,
                children = emptyList(),
                diagnostic = "The configured library path is not a regular directory.",
            )
        }

        val scanned = scanFolder(libraryRoot)
        val duplicateIds = scanned.children.asSequence()
            .flatMap(::templateEntries)
            .mapNotNull { it.summary.id?.value }
            .groupingBy(String::lowercase)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        val checked = markConflicts(scanned.children, duplicateIds)
        return LibrarySnapshot(libraryRoot, checked, scanned.diagnostic)
    }

    override fun load(directory: Path): RepositoryResult<StoredTemplate> = protect("load template") {
        val safeDirectory = requireTemplateDirectory(directory)
        val markdownPath = safeDirectory.resolve(MARKDOWN_FILE)
        if (!Files.isRegularFile(markdownPath, NOFOLLOW_LINKS)) {
            return@protect RepositoryResult.Failure("Template is missing a regular $MARKDOWN_FILE file.")
        }
        val markdown = Files.readString(markdownPath, StandardCharsets.UTF_8)
        val metadataPath = safeDirectory.resolve(METADATA_FILE)

        if (!Files.exists(metadataPath, NOFOLLOW_LINKS)) {
            val inferred = inferredMetadata(safeDirectory, markdown)
            return@protect RepositoryResult.Success(
                StoredTemplate(PromptTemplate(inferred, markdown), safeDirectory, recoverable = true),
            )
        }
        if (!Files.isRegularFile(metadataPath, NOFOLLOW_LINKS)) {
            return@protect RepositoryResult.Failure("Template metadata is not a regular file.")
        }

        when (val decoded = codec.decode(Files.readString(metadataPath, StandardCharsets.UTF_8))) {
            is MetadataDecodeResult.Success -> RepositoryResult.Success(
                StoredTemplate(PromptTemplate(decoded.metadata, markdown), safeDirectory),
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
    ): RepositoryResult<StoredTemplate> = mutateLibrary("create template") {
        val template = draft.toTemplate()
        codec.validate(template.metadata)?.let { return@mutateLibrary RepositoryResult.Failure(it) }
        val destination = requireOrganiserFolder(destinationFolder, createRoot = true)
        duplicateVisibleName(destination, template.metadata.name)?.let {
            return@mutateLibrary RepositoryResult.Failure("An entry named '${template.metadata.name}' already exists in this folder.")
        }
        duplicateId(template.id)?.let {
            return@mutateLibrary RepositoryResult.Failure("Template UUID '${template.id.value}' already exists in the library.")
        }

        val previousOrder = effectiveOrder(destination)
        val directory = nextAvailableDirectory(destination, slugify(template.metadata.name))
        Files.createDirectory(directory)
        try {
            writeTemplate(directory, template)
        } catch (error: IOException) {
            if (Files.exists(directory, NOFOLLOW_LINKS)) LibraryTreeDeletion.deleteTree(directory)
            throw error
        }
        val updatedOrder = previousOrder.withNames(
            EntryKind.TEMPLATE,
            previousOrder.templates + directory.fileName.toString(),
        )
        val warnings = persistOrderWarnings(destination, updatedOrder)
        RepositoryResult.Success(StoredTemplate(template, directory), warnings)
    }

    override fun update(
        directory: Path,
        draft: PromptTemplateDraft,
    ): RepositoryResult<StoredTemplate> = mutateLibrary("update template") {
        val safeDirectory = requireTemplateDirectory(directory)
        val template = draft.toTemplate()
        codec.validate(template.metadata)?.let { return@mutateLibrary RepositoryResult.Failure(it) }
        val stored = when (val result = load(safeDirectory)) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure -> return@mutateLibrary result
        }
        if (!stored.template.id.value.equals(template.id.value, ignoreCase = true)) {
            return@mutateLibrary RepositoryResult.Failure(
                "Refusing to overwrite a different template. Reload the library and try again.",
            )
        }
        duplicateVisibleName(safeDirectory.parent, template.metadata.name, excluding = safeDirectory)?.let {
            return@mutateLibrary RepositoryResult.Failure("An entry named '${template.metadata.name}' already exists in this folder.")
        }
        duplicateId(template.id, excluding = safeDirectory)?.let {
            return@mutateLibrary RepositoryResult.Failure("Template UUID '${template.id.value}' already exists in the library.")
        }
        writeTemplate(safeDirectory, template)
        RepositoryResult.Success(StoredTemplate(template, safeDirectory))
    }

    override fun deleteTemplate(directory: Path): RepositoryResult<Unit> = mutateLibrary("delete template") {
        val safeDirectory = requireTemplateDirectory(directory)
        val parent = safeDirectory.parent
        val previousOrder = effectiveOrder(parent)
        LibraryTreeDeletion.deleteTree(safeDirectory)
        val updated = previousOrder.removing(safeDirectory.fileName.toString(), EntryKind.TEMPLATE)
        RepositoryResult.Success(Unit, persistOrderWarnings(parent, updated))
    }

    override fun importMarkdown(
        source: Path,
        destinationFolder: Path,
    ): RepositoryResult<StoredTemplate> = mutateLibrary("import Markdown") {
        if (!Files.isRegularFile(source, NOFOLLOW_LINKS) || source.extension.lowercase() != "md") {
            return@mutateLibrary RepositoryResult.Failure("Select a regular Markdown (.md) file.")
        }
        val markdown = Files.readString(source, StandardCharsets.UTF_8)
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
    ): RepositoryResult<Path> = protect("export template Markdown") {
        val source = requireTemplateDirectory(directory).resolve(MARKDOWN_FILE)
        if (!Files.isRegularFile(source, NOFOLLOW_LINKS)) {
            return@protect RepositoryResult.Failure("Template is missing a regular $MARKDOWN_FILE file.")
        }
        ensureDestinationParent(destination)
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        RepositoryResult.Success(destination)
    }

    override fun exportRenderedMarkdown(
        rendered: String,
        destination: Path,
    ): RepositoryResult<Path> = protect("export rendered Markdown") {
        ensureDestinationParent(destination)
        atomicWrite(destination, rendered)
        RepositoryResult.Success(destination)
    }

    override fun createFolder(parent: Path, name: String): RepositoryResult<Path> = mutateLibrary("create folder") {
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
        if (safeDirectory.fileName.toString() == validName) {
            return@mutateLibrary RepositoryResult.Success(safeDirectory)
        }
        val parent = safeDirectory.parent
        duplicateVisibleName(parent, validName, excluding = safeDirectory)?.let {
            return@mutateLibrary RepositoryResult.Failure("An entry named '$validName' already exists in this folder.")
        }
        val destination = parent.resolve(validName)
        val destinationExists = Files.exists(destination, NOFOLLOW_LINKS)
        val operation = selectFolderRenameOperation(
            destinationExists = destinationExists,
            destinationRefersToSource = destinationExists && Files.isSameFile(safeDirectory, destination),
        )
        if (operation == FolderRenameOperation.COLLISION) {
            return@mutateLibrary RepositoryResult.Failure(
                "A filesystem entry named '$validName' already exists.",
            )
        }

        val previousOrder = effectiveOrder(parent)
        when (operation) {
            FolderRenameOperation.DIRECT -> moveWithoutReplacement(safeDirectory, destination)
            FolderRenameOperation.CASE_ONLY_TWO_STEP -> moveCaseOnlyFolder(safeDirectory, destination)
            FolderRenameOperation.COLLISION -> error("A collision must return before filesystem mutation.")
        }
        val updatedOrder = previousOrder.replacing(
            safeDirectory.fileName.toString(),
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
        val libraryRoot = normalizedRoot()
        require(safeEntry != libraryRoot) { "The library root cannot be moved." }
        val kind = entryKind(safeEntry)
        if (kind == EntryKind.FOLDER &&
            (safeDestination == safeEntry || safeDestination.startsWith(safeEntry))
        ) {
            return@mutateLibrary RepositoryResult.Failure("A folder cannot be moved into itself or one of its descendants.")
        }

        val sourceParent = safeEntry.parent
        val sameParent = sourceParent == safeDestination
        val visibleName = visibleName(safeEntry, kind)
        duplicateVisibleName(safeDestination, visibleName, excluding = if (sameParent) safeEntry else null)?.let {
            return@mutateLibrary RepositoryResult.Failure("An entry named '$visibleName' already exists in the destination folder.")
        }
        val target = safeDestination.resolve(safeEntry.fileName.toString())
        if (!sameParent && Files.exists(target, NOFOLLOW_LINKS)) {
            return@mutateLibrary RepositoryResult.Failure(
                "A filesystem entry named '${safeEntry.fileName}' already exists in the destination folder.",
            )
        }

        val sourceOrder = effectiveOrder(sourceParent)
        val destinationOrder = if (sameParent) sourceOrder else effectiveOrder(safeDestination)
        val placedDestinationOrder = destinationOrder.placing(
            safeEntry.fileName.toString(),
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
                addAll(persistOrderWarnings(sourceParent, sourceOrder.removing(safeEntry.fileName.toString(), kind)))
                addAll(persistOrderWarnings(safeDestination, placedDestinationOrder))
            }
        }
        RepositoryResult.Success(resultPath, warnings)
    }

    override fun previewFolderDeletion(directory: Path): RepositoryResult<FolderDeletionPreview> =
        protect("inspect folder") {
            val safeDirectory = requireOrganiserFolder(directory)
            require(safeDirectory != normalizedRoot()) { "The library root cannot be deleted." }
            RepositoryResult.Success(LibraryTreeDeletion.manifest(safeDirectory, ::isTemplatePackage).preview)
        }

    override fun deleteFolder(preview: FolderDeletionPreview): RepositoryResult<Unit> = mutateLibrary("delete folder") {
        val safeDirectory = requireOrganiserFolder(preview.directory)
        require(safeDirectory != normalizedRoot()) { "The library root cannot be deleted." }
        val current = LibraryTreeDeletion.manifest(safeDirectory, ::isTemplatePackage)
        if (current.preview != preview.copy(directory = safeDirectory)) {
            return@mutateLibrary RepositoryResult.Failure(
                "The folder contents changed after confirmation. Review the folder and confirm deletion again.",
            )
        }

        val parent = safeDirectory.parent
        val previousOrder = effectiveOrder(parent)
        LibraryTreeDeletion.deleteTree(safeDirectory)
        val updated = previousOrder.removing(safeDirectory.fileName.toString(), EntryKind.FOLDER)
        RepositoryResult.Success(Unit, persistOrderWarnings(parent, updated))
    }

    private fun scanFolder(directory: Path): ScannedFolder {
        val children = try {
            Files.newDirectoryStream(directory).use { stream ->
                stream.asSequence()
                    .filter(::isScannableDirectoryEntry)
                    .map { child ->
                        when {
                            Files.isSymbolicLink(child) -> LibraryEntry.Folder(
                                directory = child.toAbsolutePath().normalize(),
                                relativeDirectory = relativeToRoot(child),
                                displayName = child.fileName.toString(),
                                children = emptyList(),
                                diagnostic = "Symbolic-link entries are not supported.",
                            )

                            isTemplatePackage(child) -> LibraryEntry.Template(
                                summary = summaryFor(child),
                                relativeDirectory = relativeToRoot(child),
                            )

                            else -> {
                                val nested = scanFolder(child)
                                LibraryEntry.Folder(
                                    directory = child.toAbsolutePath().normalize(),
                                    relativeDirectory = relativeToRoot(child),
                                    displayName = child.fileName.toString(),
                                    children = nested.children,
                                    diagnostic = nested.diagnostic,
                                )
                            }
                        }
                    }
                    .toList()
            }
        } catch (error: IOException) {
            return ScannedFolder(emptyList(), "Unable to read folder: ${error.message}")
        } catch (error: DirectoryIteratorException) {
            return ScannedFolder(emptyList(), "Unable to read folder: ${error.cause?.message ?: error.message}")
        } catch (error: SecurityException) {
            return ScannedFolder(emptyList(), "Unable to read folder: permission denied.")
        }

        val order = LibraryFolderOrderCodec.read(directory)
        return ScannedFolder(
            children = sortEntries(children, order.value),
            diagnostic = order.diagnostic,
        )
    }

    private fun markConflicts(
        entries: List<LibraryEntry>,
        duplicateIds: Set<String>,
    ): List<LibraryEntry> {
        val siblingDuplicates = entries
            .groupingBy { it.displayName.normalizedVisibleName() }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        return entries.map { entry ->
            val siblingConflict = entry.displayName.normalizedVisibleName() in siblingDuplicates
            when (entry) {
                is LibraryEntry.Folder -> entry.copy(
                    children = markConflicts(entry.children, duplicateIds),
                    diagnostic = combineDiagnostics(
                        entry.diagnostic,
                        if (siblingConflict) "Duplicate sibling name '${entry.displayName}'." else null,
                    ),
                )

                is LibraryEntry.Template -> {
                    val duplicateId = entry.summary.id?.value?.lowercase() in duplicateIds
                    val diagnostic = combineDiagnostics(
                        entry.summary.diagnostic,
                        if (duplicateId) "Duplicate template UUID ${entry.summary.id?.value}." else null,
                        if (siblingConflict) "Duplicate sibling name '${entry.displayName}'." else null,
                    )
                    if (diagnostic == entry.summary.diagnostic) {
                        entry
                    } else {
                        entry.copy(summary = entry.summary.copy(health = TemplateHealth.BROKEN, diagnostic = diagnostic))
                    }
                }
            }
        }
    }

    private fun summaryFor(directory: Path): TemplateSummary {
        val markdownPath = directory.resolve(MARKDOWN_FILE)
        val metadataPath = directory.resolve(METADATA_FILE)
        val markdownEntryExists = Files.exists(markdownPath, NOFOLLOW_LINKS)
        val metadataEntryExists = Files.exists(metadataPath, NOFOLLOW_LINKS)
        val markdownIsRegular = Files.isRegularFile(markdownPath, NOFOLLOW_LINKS)
        val metadataIsRegular = Files.isRegularFile(metadataPath, NOFOLLOW_LINKS)

        if (!metadataEntryExists) {
            return TemplateSummary(
                id = null,
                name = directory.fileName.toString(),
                description = null,
                tags = emptyList(),
                directory = directory.toAbsolutePath().normalize(),
                health = if (markdownIsRegular) TemplateHealth.RECOVERABLE else TemplateHealth.BROKEN,
                diagnostic = when {
                    markdownIsRegular -> "Metadata is missing and can be regenerated."
                    markdownEntryExists -> "$MARKDOWN_FILE is not a regular file."
                    else -> "Template is missing both canonical files."
                },
            )
        }
        if (!metadataIsRegular) {
            return TemplateSummary(
                id = null,
                name = directory.fileName.toString(),
                description = null,
                tags = emptyList(),
                directory = directory.toAbsolutePath().normalize(),
                health = TemplateHealth.BROKEN,
                diagnostic = "$METADATA_FILE is not a regular file.",
            )
        }

        val decoded = runCatching {
            codec.decode(Files.readString(metadataPath, StandardCharsets.UTF_8))
        }.getOrElse { error ->
            return TemplateSummary(
                null,
                directory.fileName.toString(),
                null,
                emptyList(),
                directory.toAbsolutePath().normalize(),
                TemplateHealth.BROKEN,
                "Unable to read metadata: ${error.message}",
            )
        }

        return when (decoded) {
            is MetadataDecodeResult.Success -> TemplateSummary(
                id = TemplateId(decoded.metadata.id),
                name = decoded.metadata.name,
                description = decoded.metadata.description,
                tags = decoded.metadata.tags,
                directory = directory.toAbsolutePath().normalize(),
                health = if (markdownIsRegular) TemplateHealth.HEALTHY else TemplateHealth.BROKEN,
                diagnostic = when {
                    markdownIsRegular -> null
                    markdownEntryExists -> "$MARKDOWN_FILE is not a regular file."
                    else -> "Template is missing $MARKDOWN_FILE."
                },
            )

            is MetadataDecodeResult.Invalid -> TemplateSummary(
                null,
                directory.fileName.toString(),
                null,
                emptyList(),
                directory.toAbsolutePath().normalize(),
                TemplateHealth.BROKEN,
                decoded.message,
            )

            is MetadataDecodeResult.UnsupportedVersion -> TemplateSummary(
                null,
                directory.fileName.toString(),
                null,
                emptyList(),
                directory.toAbsolutePath().normalize(),
                TemplateHealth.BROKEN,
                "Unsupported metadata schema ${decoded.found}; opened read-only.",
            )
        }
    }

    private fun inferredMetadata(directory: Path, markdown: String): TemplateMetadata {
        val id = UUID.nameUUIDFromBytes(directory.toAbsolutePath().normalize().toString().toByteArray()).toString()
        val variables = parser.parse(markdown).placeholders
            .filterNot(PlaceholderToken::contextReference)
            .map(PlaceholderToken::key)
            .distinct()
            .map { key -> PromptVariable(key = key, label = defaultVariableLabel(key)) }
        return TemplateMetadata(
            id = id,
            name = firstHeading(markdown) ?: directory.fileName.toString(),
            variables = variables,
        )
    }

    private fun writeTemplate(directory: Path, template: PromptTemplate) {
        atomicWrite(directory.resolve(MARKDOWN_FILE), template.markdown)
        atomicWrite(directory.resolve(METADATA_FILE), codec.encode(template.metadata))
    }

    private fun atomicWrite(destination: Path, content: String) {
        val parent = requireNotNull(destination.parent) { "A destination parent is required." }
        val temporary = Files.createTempFile(parent, ".${destination.fileName}.", ".tmp")
        try {
            Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun requireTemplateDirectory(directory: Path): Path {
        val safeDirectory = requireExistingManagedDirectory(directory, allowRoot = false)
        require(isTemplatePackage(safeDirectory)) { "The selected entry is an organiser folder, not a template." }
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
            require(!isTemplatePackage(safeDirectory)) { "Templates cannot contain organiser folders." }
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
        require(normalPath == libraryRoot || !Files.isSymbolicLink(normalPath)) {
            "Symbolic-link entries are not supported."
        }
        require(Files.isDirectory(normalPath)) { "Library entry is not a directory." }

        var current = libraryRoot
        libraryRoot.relativize(normalPath).forEach { segment ->
            if (segment.toString().isEmpty()) return@forEach
            require(!isLibraryManagementDirectoryName(segment.toString())) {
                "IDE and version-control metadata directories are not part of the template library."
            }
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "Symbolic-link paths are not supported." }
            require(Files.isDirectory(current, NOFOLLOW_LINKS)) { "Library path is not a directory." }
            require(current == normalPath || !isTemplatePackage(current)) {
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

    private fun relativeToRoot(path: Path): Path = normalizedRoot().relativize(path.toAbsolutePath().normalize())

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
    ): DirectEntry? {
        val excluded = excluding?.toAbsolutePath()?.normalize()
        return directEntries(parent).firstOrNull { candidate ->
            candidate.path != excluded && candidate.visibleName.trim().equals(name.trim(), ignoreCase = true)
        }
    }

    private fun duplicateId(id: TemplateId, excluding: Path? = null): LibraryEntry.Template? {
        val excluded = excluding?.toAbsolutePath()?.normalize()
        return scan().children.asSequence()
            .flatMap(::templateEntries)
            .firstOrNull { entry ->
                entry.directory != excluded && entry.summary.id?.value.equals(id.value, ignoreCase = true)
            }
    }

    private fun directEntries(parent: Path): List<DirectEntry> = Files.newDirectoryStream(parent).use { stream ->
        stream.asSequence()
            .filter(::isScannableDirectoryEntry)
            .map { path ->
                val kind = if (!Files.isSymbolicLink(path) && isTemplatePackage(path)) {
                    EntryKind.TEMPLATE
                } else {
                    EntryKind.FOLDER
                }
                DirectEntry(
                    path = path.toAbsolutePath().normalize(),
                    kind = kind,
                    visibleName = visibleName(path, kind),
                )
            }
            .toList()
    }

    private fun visibleName(path: Path, kind: EntryKind): String = when (kind) {
        EntryKind.FOLDER -> path.fileName.toString()
        EntryKind.TEMPLATE -> summaryFor(path).name
    }

    private fun entryKind(path: Path): EntryKind =
        if (isTemplatePackage(path)) EntryKind.TEMPLATE else EntryKind.FOLDER

    private fun isScannableDirectoryEntry(path: Path): Boolean =
        path.fileName.toString() != ORDER_FILE &&
            !isLibraryManagementDirectoryName(path.fileName.toString()) &&
            (Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path))

    private fun isTemplatePackage(directory: Path): Boolean =
        Files.exists(directory.resolve(MARKDOWN_FILE), NOFOLLOW_LINKS) ||
            Files.exists(directory.resolve(METADATA_FILE), NOFOLLOW_LINKS)

    private fun requireFolderName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Folder name is required." }
        require(trimmed == name) { "Folder names cannot start or end with whitespace." }
        require(trimmed != "." && trimmed != "..") { "Folder name is not valid." }
        require(trimmed.none { it.code < 32 || it in INVALID_FOLDER_NAME_CHARACTERS }) {
            "Folder name contains a character that is not portable across supported systems."
        }
        require(!trimmed.endsWith('.')) { "Folder names cannot end with a period." }
        require(
            trimmed.lowercase(Locale.ROOT) !in RESERVED_ENTRY_NAMES,
        ) { "'$trimmed' is reserved by the prompt-template library." }
        require(!isLibraryManagementDirectoryName(trimmed)) {
            "'$trimmed' is reserved for IDE or version-control metadata."
        }
        return trimmed
    }

    private fun effectiveOrder(folder: Path): FolderOrderState {
        val entries = directEntries(folder)
        val read = LibraryFolderOrderCodec.read(folder)
        val sorted = sortDirectEntries(entries, read.value)
        return FolderOrderState(
            folders = sorted.filter { it.kind == EntryKind.FOLDER }.map { it.path.fileName.toString() },
            templates = sorted.filter { it.kind == EntryKind.TEMPLATE }.map { it.path.fileName.toString() },
        )
    }

    private fun persistOrder(folder: Path, order: FolderOrderState) {
        val encoded = LibraryFolderOrderCodec.encode(order)
        atomicWrite(folder.resolve(ORDER_FILE), encoded)
    }

    private fun persistOrderWarnings(folder: Path, order: FolderOrderState): List<String> = try {
        persistOrder(folder, order)
        emptyList()
    } catch (error: IOException) {
        listOf("The library change succeeded, but folder order could not be saved: ${error.message}")
    } catch (error: SecurityException) {
        listOf("The library change succeeded, but folder order could not be saved: permission denied.")
    }

    private fun sortEntries(entries: List<LibraryEntry>, order: FolderOrderFile?): List<LibraryEntry> =
        entries.sortedWith(
            LibraryFolderOrderCodec.comparator(
                order = order,
                kindOf = { if (it is LibraryEntry.Folder) EntryKind.FOLDER else EntryKind.TEMPLATE },
                orderKeyOf = { it.directory.fileName.toString() },
                fallbackNameOf = LibraryEntry::displayName,
            ),
        )

    private fun sortDirectEntries(entries: List<DirectEntry>, order: FolderOrderFile?): List<DirectEntry> =
        entries.sortedWith(
            LibraryFolderOrderCodec.comparator(
                order = order,
                kindOf = DirectEntry::kind,
                orderKeyOf = { it.path.fileName.toString() },
                fallbackNameOf = DirectEntry::visibleName,
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
        val index = when (placement) {
            EntryPlacement.EndOfKind -> names.size
            is EntryPlacement.Before -> placementIndex(
                sibling = placement.sibling,
                destinationFolder = destinationFolder,
                source = source,
                kind = kind,
                names = names,
                after = false,
            )

            is EntryPlacement.After -> placementIndex(
                sibling = placement.sibling,
                destinationFolder = destinationFolder,
                source = source,
                kind = kind,
                names = names,
                after = true,
            )
        }
        names.add(index, name)
        return withNames(kind, names)
    }

    private fun placementIndex(
        sibling: Path,
        destinationFolder: Path,
        source: Path,
        kind: EntryKind,
        names: List<String>,
        after: Boolean,
    ): Int {
        val safeSibling = requireLibraryEntry(sibling)
        require(safeSibling.parent == destinationFolder) { "The placement target is not in the destination folder." }
        require(safeSibling != source) { "An entry cannot be placed relative to itself." }
        require(entryKind(safeSibling) == kind) { "Folders and templates cannot be interleaved." }
        val siblingIndex = names.indexOf(safeSibling.fileName.toString())
        require(siblingIndex >= 0) { "The placement target is no longer available." }
        return siblingIndex + if (after) 1 else 0
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
            val candidate = parent.resolve("$CASE_RENAME_PREFIX${UUID.randomUUID()}")
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
                "Unable to apply the requested folder-name casing. The folder remains at '$temporary'.",
                renameError,
            )
        }
        return try {
            moveWithoutReplacement(temporary, source)
            renameError
        } catch (rollbackError: IOException) {
            IOException(
                "Unable to apply the requested folder-name casing or restore the original name. " +
                    "The folder remains at '$temporary'.",
                renameError,
            ).apply { addSuppressed(rollbackError) }
        } catch (rollbackError: SecurityException) {
            IOException(
                "Unable to apply the requested folder-name casing or restore the original name. " +
                    "The folder remains at '$temporary'.",
                renameError,
            ).apply { addSuppressed(rollbackError) }
        }
    }

    private fun ensureDestinationParent(destination: Path) {
        destination.parent?.let(Files::createDirectories)
    }

    private fun slugify(name: String): String = name
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "prompt-template" }

    private fun firstHeading(markdown: String): String? = markdown.lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("# ") }
        ?.removePrefix("# ")
        ?.trim()
        ?.ifEmpty { null }

    private inline fun <T> protect(operation: String, block: () -> RepositoryResult<T>): RepositoryResult<T> =
        protectRepositoryOperation(operation, block)

    private inline fun <T> mutateLibrary(
        operation: String,
        block: () -> RepositoryResult<T>,
    ): RepositoryResult<T> = mutationLock().withLock {
        protect(operation, block)
    }

    private fun mutationLock(): ReentrantLock {
        val key = stableMutationLockKey(normalizedRoot())
        return LIBRARY_MUTATION_LOCKS.computeIfAbsent(key) { ReentrantLock() }
    }

    private fun stableMutationLockKey(path: Path): Path {
        var existingAncestor: Path? = path
        val missingSegments = ArrayDeque<Path>()
        while (existingAncestor != null && !Files.exists(existingAncestor, NOFOLLOW_LINKS)) {
            existingAncestor.fileName?.let(missingSegments::addFirst)
            existingAncestor = existingAncestor.parent
        }
        val canonicalAncestor = existingAncestor?.let { ancestor ->
            runCatching { ancestor.toRealPath() }.getOrDefault(ancestor.toAbsolutePath().normalize())
        } ?: path.root
        return missingSegments.fold(canonicalAncestor) { current, segment -> current.resolve(segment) }.normalize()
    }

    private fun templateEntries(entry: LibraryEntry): Sequence<LibraryEntry.Template> = when (entry) {
        is LibraryEntry.Template -> sequenceOf(entry)
        is LibraryEntry.Folder -> entry.children.asSequence().flatMap(::templateEntries)
    }

    private fun combineDiagnostics(vararg values: String?): String? =
        values.filterNotNull().filter(String::isNotBlank).distinct().joinToString(" ").ifBlank { null }

    private fun String.normalizedVisibleName(): String = trim().lowercase(Locale.ROOT)

    private data class ScannedFolder(
        val children: List<LibraryEntry>,
        val diagnostic: String? = null,
    )

    private data class DirectEntry(
        val path: Path,
        val kind: EntryKind,
        val visibleName: String,
    )

    companion object {
        const val MARKDOWN_FILE = "prompt.md"
        const val METADATA_FILE = "prompt.meta.json"
        const val ORDER_FILE = LIBRARY_ORDER_FILE
        const val ORDER_SCHEMA_VERSION = LIBRARY_ORDER_SCHEMA_VERSION

        private val RESERVED_ENTRY_NAMES = setOf(MARKDOWN_FILE, METADATA_FILE, ORDER_FILE)
            .mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        private val LIBRARY_MANAGEMENT_DIRECTORY_NAMES = setOf(".git", ".hg", ".svn", ".idea")
        private val INVALID_FOLDER_NAME_CHARACTERS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        private val LIBRARY_MUTATION_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
        private const val CASE_RENAME_PREFIX = ".prompt-template-rename-"

        fun isLibraryManagementDirectoryName(name: String): Boolean =
            name.lowercase(Locale.ROOT) in LIBRARY_MANAGEMENT_DIRECTORY_NAMES
    }
}
