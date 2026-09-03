package dev.timbrinded.prompttemplates.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal const val LIBRARY_ORDER_FILE = ".prompt-templates-order.json"
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

internal object LibraryFolderOrderCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun read(folder: Path): ReadOrder = try {
        readOrderFile(folder)
    } catch (_: SecurityException) {
        ReadOrder(diagnostic = "Unable to read $LIBRARY_ORDER_FILE: permission denied; alphabetical order is in use.")
    }

    private fun readOrderFile(folder: Path): ReadOrder {
        val path = folder.resolve(LIBRARY_ORDER_FILE)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return ReadOrder()
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            return ReadOrder(
                diagnostic = "$LIBRARY_ORDER_FILE is not a regular file; alphabetical order is in use.",
            )
        }
        val decoded = try {
            json.decodeFromString(FolderOrderFile.serializer(), Files.readString(path, Charsets.UTF_8))
        } catch (_: IllegalArgumentException) {
            return invalidOrder()
        } catch (error: IOException) {
            return ReadOrder(
                diagnostic = "Unable to read $LIBRARY_ORDER_FILE: ${error.message}; alphabetical order is in use.",
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
        return Comparator { left, right ->
            compare(
                leftKind = kindOf(left),
                rightKind = kindOf(right),
                leftOrderKey = orderKeyOf(left),
                rightOrderKey = orderKeyOf(right),
                leftFallbackName = fallbackNameOf(left),
                rightFallbackName = fallbackNameOf(right),
                folderPositions = folderPositions,
                templatePositions = templatePositions,
            )
        }
    }

    private fun compare(
        leftKind: EntryKind,
        rightKind: EntryKind,
        leftOrderKey: String,
        rightOrderKey: String,
        leftFallbackName: String,
        rightFallbackName: String,
        folderPositions: Map<String, Int>,
        templatePositions: Map<String, Int>,
    ): Int {
        if (leftKind != rightKind) return leftKind.ordinal.compareTo(rightKind.ordinal)
        val positions = if (leftKind == EntryKind.FOLDER) folderPositions else templatePositions
        val leftIndex = positions[leftOrderKey]
        val rightIndex = positions[rightOrderKey]
        val byStoredOrder = when {
            leftIndex != null && rightIndex != null -> leftIndex.compareTo(rightIndex)
            leftIndex != null -> -1
            rightIndex != null -> 1
            else -> 0
        }
        if (byStoredOrder != 0) return byStoredOrder
        val byName = String.CASE_INSENSITIVE_ORDER.compare(leftFallbackName, rightFallbackName)
        if (byName != 0) return byName
        val byExactName = leftFallbackName.compareTo(rightFallbackName)
        if (byExactName != 0) return byExactName
        val byKey = String.CASE_INSENSITIVE_ORDER.compare(leftOrderKey, rightOrderKey)
        return if (byKey != 0) byKey else leftOrderKey.compareTo(rightOrderKey)
    }

    private fun invalidOrder(): ReadOrder =
        ReadOrder(diagnostic = "$LIBRARY_ORDER_FILE is invalid; alphabetical order is in use.")

    private fun hasValidNames(order: FolderOrderFile): Boolean {
        val all = order.folders + order.templates
        return all.none { name ->
            name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')
        } && all.size == all.distinct().size
    }
}
