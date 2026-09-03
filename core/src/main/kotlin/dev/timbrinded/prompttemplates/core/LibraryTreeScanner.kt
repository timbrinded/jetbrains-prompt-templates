package dev.timbrinded.prompttemplates.core

import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository.Companion.MARKDOWN_FILE
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository.Companion.METADATA_FILE
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository.Companion.ORDER_FILE
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository.Companion.isInternalLibraryEntryName
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.useDirectoryEntries

internal data class DirectLibraryEntry(
    val path: Path,
    val kind: EntryKind,
    val visibleName: String,
)

internal class LibraryTreeScanner(
    root: Path,
    private val codec: TemplateMetadataCodec,
) {
    private val root = root.toAbsolutePath().normalize()

    fun scan(): LibrarySnapshot {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return LibrarySnapshot(root, emptyList())
        if (!Files.isDirectory(root)) {
            return LibrarySnapshot(
                root = root,
                children = emptyList(),
                diagnostic = "The configured library path is not a regular directory.",
            )
        }

        val scanned = scanFolder(root)
        val duplicateIds = templates(scanned.children)
            .mapNotNull { it.summary.id?.value }
            .groupingBy(String::lowercase)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        return LibrarySnapshot(root, markConflicts(scanned.children, duplicateIds), scanned.diagnostic)
    }

    fun directEntries(parent: Path): List<DirectLibraryEntry> = parent.useDirectoryEntries { entries ->
        entries
            .filter(::isScannableDirectoryEntry)
            .map(::classify)
            .toList()
    }

    fun classify(path: Path): DirectLibraryEntry {
        val kind = if (!Files.isSymbolicLink(path) && isTemplatePackage(path)) {
            EntryKind.TEMPLATE
        } else {
            EntryKind.FOLDER
        }
        return DirectLibraryEntry(
            path = path.toAbsolutePath().normalize(),
            kind = kind,
            visibleName = when (kind) {
                EntryKind.FOLDER -> path.name
                EntryKind.TEMPLATE -> summaryFor(path).name
            },
        )
    }

    fun templateWithId(id: TemplateId, excluding: Path? = null): LibraryEntry.Template? {
        val excluded = excluding?.toAbsolutePath()?.normalize()
        return templates(scan().children).firstOrNull { entry ->
            entry.directory != excluded && entry.summary.id?.value.equals(id.value, ignoreCase = true)
        }
    }

    fun isTemplatePackage(directory: Path): Boolean =
        Files.exists(directory.resolve(MARKDOWN_FILE), NOFOLLOW_LINKS) ||
            Files.exists(directory.resolve(METADATA_FILE), NOFOLLOW_LINKS)

    private fun scanFolder(directory: Path): ScannedFolder {
        val children = try {
            directory.useDirectoryEntries { entries ->
                entries
                    .filter(::isScannableDirectoryEntry)
                    .map { child ->
                        when {
                            Files.isSymbolicLink(child) -> LibraryEntry.Folder(
                                directory = child.toAbsolutePath().normalize(),
                                relativeDirectory = relativeToRoot(child),
                                displayName = child.name,
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
                                    displayName = child.name,
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
        } catch (_: SecurityException) {
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
            return diagnosticSummary(
                directory = directory,
                health = if (markdownIsRegular) TemplateHealth.RECOVERABLE else TemplateHealth.BROKEN,
                diagnostic = when {
                    markdownIsRegular -> "Metadata is missing and can be regenerated."
                    markdownEntryExists -> "$MARKDOWN_FILE is not a regular file."
                    else -> "Template is missing both canonical files."
                },
            )
        }
        if (!metadataIsRegular) {
            return diagnosticSummary(directory, TemplateHealth.BROKEN, "$METADATA_FILE is not a regular file.")
        }

        val rawMetadata = try {
            Files.readString(metadataPath, Charsets.UTF_8)
        } catch (error: IOException) {
            return diagnosticSummary(directory, TemplateHealth.BROKEN, "Unable to read metadata: ${error.message}")
        } catch (error: SecurityException) {
            return diagnosticSummary(directory, TemplateHealth.BROKEN, "Unable to read metadata: ${error.message}")
        }
        val decoded = codec.decode(rawMetadata)

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

            is MetadataDecodeResult.Invalid ->
                diagnosticSummary(directory, TemplateHealth.BROKEN, decoded.message)

            is MetadataDecodeResult.UnsupportedVersion -> diagnosticSummary(
                directory,
                TemplateHealth.BROKEN,
                "Unsupported metadata schema ${decoded.found}; opened read-only.",
            )
        }
    }

    private fun diagnosticSummary(
        directory: Path,
        health: TemplateHealth,
        diagnostic: String,
    ): TemplateSummary = TemplateSummary(
        id = null,
        name = directory.name,
        description = null,
        tags = emptyList(),
        directory = directory.toAbsolutePath().normalize(),
        health = health,
        diagnostic = diagnostic,
    )

    private fun isScannableDirectoryEntry(path: Path): Boolean =
        path.name != ORDER_FILE &&
            !isInternalLibraryEntryName(path.name) &&
            (Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path))

    private fun sortEntries(entries: List<LibraryEntry>, order: FolderOrderFile?): List<LibraryEntry> =
        entries.sortedWith(
            LibraryFolderOrderCodec.comparator(
                order = order,
                kindOf = { if (it is LibraryEntry.Folder) EntryKind.FOLDER else EntryKind.TEMPLATE },
                orderKeyOf = { it.directory.name },
                fallbackNameOf = LibraryEntry::displayName,
            ),
        )

    private fun templates(entries: List<LibraryEntry>): Sequence<LibraryEntry.Template> =
        entries.asSequence().flatMap { entry ->
            when (entry) {
                is LibraryEntry.Template -> sequenceOf(entry)
                is LibraryEntry.Folder -> templates(entry.children)
            }
        }

    private fun relativeToRoot(path: Path): Path = root.relativize(path.toAbsolutePath().normalize())

    private fun combineDiagnostics(vararg values: String?): String? =
        values.filterNotNull().filter(String::isNotBlank).distinct().joinToString(" ").ifBlank { null }

    private fun String.normalizedVisibleName(): String = trim().lowercase()

    private data class ScannedFolder(
        val children: List<LibraryEntry>,
        val diagnostic: String? = null,
    )
}
