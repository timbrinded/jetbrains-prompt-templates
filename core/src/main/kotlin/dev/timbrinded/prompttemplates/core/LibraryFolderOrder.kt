package dev.timbrinded.prompttemplates.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.name

internal const val LIBRARY_ORDER_SCHEMA_VERSION = 1

internal enum class EntryKind { FOLDER, TEMPLATE }

@Serializable
internal data class FolderOrderFile(
    val schemaVersion: Int = LIBRARY_ORDER_SCHEMA_VERSION,
    val folders: List<String> = emptyList(),
    val templates: List<String> = emptyList(),
)

internal data class ReadOrder(
    val value: FolderOrderFile? = null,
    val diagnostic: String? = null,
)

internal data class FolderOrderState(
    val folders: List<String>,
    val templates: List<String>,
    /** Why the existing order file could not be read. Such a file is never replaced. */
    val unreadable: String? = null,
) {
    fun names(kind: EntryKind): List<String> = when (kind) {
        EntryKind.FOLDER -> folders
        EntryKind.TEMPLATE -> templates
    }

    fun withNames(kind: EntryKind, names: List<String>): FolderOrderState = when (kind) {
        EntryKind.FOLDER -> copy(folders = names)
        EntryKind.TEMPLATE -> copy(templates = names)
    }

    fun removing(name: String, kind: EntryKind): FolderOrderState =
        withNames(kind, names(kind).filterNot { it == name })

    fun replacing(oldName: String, newName: String, kind: EntryKind): FolderOrderState =
        withNames(kind, names(kind).map { if (it == oldName) newName else it })
}

/** Reads and persists the manual order of organiser folders. Callers hold the library lock. */
internal class FolderOrderStore(
    private val scanner: LibraryTreeScanner,
    private val paths: LibraryPaths,
) {
    fun effective(folder: Path): FolderOrderState {
        val read = LibraryFolderOrderCodec.read(folder)
        val sorted = scanner.directEntries(folder).sortedWith(
            LibraryFolderOrderCodec.comparator(
                order = read.value,
                kindOf = DirectLibraryEntry::kind,
                orderKeyOf = { it.path.name },
                fallbackNameOf = DirectLibraryEntry::visibleName,
            ),
        )
        return FolderOrderState(
            folders = sorted.filter { it.kind == EntryKind.FOLDER }.map { it.path.name },
            templates = sorted.filter { it.kind == EntryKind.TEMPLATE }.map { it.path.name },
            unreadable = read.diagnostic,
        )
    }

    fun persist(folder: Path, order: FolderOrderState) {
        // A malformed or newer-schema order file may hold order this version cannot represent.
        order.unreadable?.let { throw IOException("$it The existing order file was left unchanged.") }
        val encoded = LibraryFolderOrderCodec.encode(order)
        writeTextAtomically(folder.resolve(LibraryLayout.ORDER_FILE), encoded, allowNonAtomicMove = true)
    }

    /** Persists order after a content change that already succeeded, so a failure is only a warning. */
    fun persistAfterChange(folder: Path, order: FolderOrderState): List<String> = try {
        persist(folder, order)
        emptyList()
    } catch (error: IOException) {
        listOf("The library change succeeded, but folder order could not be saved: ${error.message}")
    } catch (error: SecurityException) {
        listOf("The library change succeeded, but folder order could not be saved: permission denied.")
    }

    fun placing(
        order: FolderOrderState,
        name: String,
        kind: EntryKind,
        placement: EntryPlacement,
        destinationFolder: Path,
        source: Path,
    ): FolderOrderState {
        val names = order.names(kind).toMutableList().also { it.remove(name) }
        names.add(placementIndex(placement, destinationFolder, source, kind, names), name)
        return order.withNames(kind, names)
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
        val safeSibling = paths.requireEntry(sibling)
        require(safeSibling.parent == destinationFolder) { "The placement target is not in the destination folder." }
        require(safeSibling != source) { "An entry cannot be placed relative to itself." }
        require(scanner.classify(safeSibling).kind == kind) { "Folders and templates cannot be interleaved." }
        val siblingIndex = names.indexOf(safeSibling.name)
        require(siblingIndex >= 0) { "The placement target is no longer available." }
        return siblingIndex + if (placement is EntryPlacement.After) 1 else 0
    }
}

internal object LibraryFolderOrderCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun read(folder: Path): ReadOrder = try {
        readOrderFile(folder)
    } catch (_: SecurityException) {
        ReadOrder(diagnostic = "Unable to read ${LibraryLayout.ORDER_FILE}: permission denied; alphabetical order is in use.")
    }

    private fun readOrderFile(folder: Path): ReadOrder {
        val path = folder.resolve(LibraryLayout.ORDER_FILE)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return ReadOrder()
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            return ReadOrder(
                diagnostic = "${LibraryLayout.ORDER_FILE} is not a regular file; alphabetical order is in use.",
            )
        }
        val decoded = try {
            json.decodeFromString(FolderOrderFile.serializer(), Files.readString(path, Charsets.UTF_8))
        } catch (_: IllegalArgumentException) {
            return invalidOrder()
        } catch (error: IOException) {
            return ReadOrder(
                diagnostic = "Unable to read ${LibraryLayout.ORDER_FILE}: ${error.message}; alphabetical order is in use.",
            )
        }
        if (decoded.schemaVersion != LIBRARY_ORDER_SCHEMA_VERSION) {
            return ReadOrder(
                diagnostic =
                    "Unsupported folder-order schema ${decoded.schemaVersion}; alphabetical order is in use.",
            )
        }
        if (!hasValidNames(decoded)) return invalidOrder()
        return ReadOrder(decoded)
    }

    fun encode(order: FolderOrderState): String = json.encodeToString(
        FolderOrderFile.serializer(),
        FolderOrderFile(folders = order.folders, templates = order.templates),
    ) + "\n"

    fun <T> comparator(
        order: FolderOrderFile?,
        kindOf: (T) -> EntryKind,
        orderKeyOf: (T) -> String,
        fallbackNameOf: (T) -> String,
    ): Comparator<T> {
        val folderPositions = order?.folders?.withIndex()?.associate { it.value to it.index }.orEmpty()
        val templatePositions = order?.templates?.withIndex()?.associate { it.value to it.index }.orEmpty()
        val positionOf: (T) -> Int? = { entry ->
            val positions = when (kindOf(entry)) {
                EntryKind.FOLDER -> folderPositions
                EntryKind.TEMPLATE -> templatePositions
            }
            positions[orderKeyOf(entry)]
        }
        return compareBy<T> { kindOf(it) }
            .thenBy(nullsLast(naturalOrder()), positionOf)
            .thenBy(String.CASE_INSENSITIVE_ORDER, fallbackNameOf)
            .thenBy(fallbackNameOf)
            .thenBy(String.CASE_INSENSITIVE_ORDER, orderKeyOf)
            .thenBy(orderKeyOf)
    }

    private fun invalidOrder(): ReadOrder =
        ReadOrder(diagnostic = "${LibraryLayout.ORDER_FILE} is invalid; alphabetical order is in use.")

    private fun hasValidNames(order: FolderOrderFile): Boolean {
        val all = order.folders + order.templates
        return all.none { name ->
            name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')
        } && all.size == all.distinct().size
    }
}
