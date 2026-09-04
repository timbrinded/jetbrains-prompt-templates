package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.EntryPlacement
import dev.timbrinded.prompttemplates.core.LibraryEntry
import dev.timbrinded.prompttemplates.core.LibrarySnapshot
import dev.timbrinded.prompttemplates.core.TemplateHealth
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal fun flattenTemplates(entries: List<LibraryEntry>): List<LibraryEntry.Template> = buildList {
    fun visit(items: List<LibraryEntry>) {
        items.forEach { entry ->
            when (entry) {
                is LibraryEntry.Folder -> visit(entry.children)
                is LibraryEntry.Template -> add(entry)
            }
        }
    }
    visit(entries)
}

internal fun flattenFolders(entries: List<LibraryEntry>): List<LibraryEntry.Folder> = buildList {
    fun visit(items: List<LibraryEntry>) {
        items.forEach { entry ->
            if (entry is LibraryEntry.Folder) {
                add(entry)
                visit(entry.children)
            }
        }
    }
    visit(entries)
}

internal fun resolveLibrarySelection(
    snapshot: LibrarySnapshot,
    key: LibrarySelectionKey?,
): LibraryTreeSelection? {
    key ?: return null
    return when (key) {
        is LibrarySelectionKey.Folder -> flattenFolders(snapshot.children)
            .firstOrNull { entry -> portablePath(entry.relativeDirectory) == key.relativePath }
            ?.let(LibraryTreeSelection::Folder)
        is LibrarySelectionKey.TemplatePath -> flattenTemplates(snapshot.children)
            .firstOrNull { entry -> portablePath(entry.relativeDirectory) == key.relativePath }
            ?.let(LibraryTreeSelection::Template)
        is LibrarySelectionKey.Template -> {
            val templates = flattenTemplates(snapshot.children)
            val exactPath = key.relativePath?.let { relativePath ->
                templates.firstOrNull { entry ->
                    portablePath(entry.relativeDirectory) == relativePath &&
                        entry.summary.id?.value.equals(key.templateId, ignoreCase = true)
                }
            }
            val resolved = exactPath ?: templates.filter { entry ->
                entry.summary.id?.value.equals(key.templateId, ignoreCase = true)
            }.singleOrNull()
            resolved?.let(LibraryTreeSelection::Template)
        }
    }
}

internal fun readSearchIndexBody(markdownPath: Path): String {
    if (!Files.isRegularFile(markdownPath, NOFOLLOW_LINKS)) return ""
    return try {
        Files.newByteChannel(markdownPath, setOf(StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
            Channels.newReader(channel, Charsets.UTF_8).readText()
        }
    } catch (_: IOException) {
        ""
    } catch (_: SecurityException) {
        ""
    } catch (_: UnsupportedOperationException) {
        ""
    }
}

internal fun resolveTemplateEntry(
    target: TemplateDetailTarget,
    templates: List<LibraryEntry.Template>,
): LibraryEntry.Template? {
    val exactPath = templates.firstOrNull { entry ->
        entry.directory == target.directory &&
            (
                target.templateId == null ||
                    entry.summary.id?.value.equals(target.templateId, ignoreCase = true) ||
                    (entry.summary.id == null && entry.summary.health == TemplateHealth.RECOVERABLE)
                )
    }
    if (exactPath != null) return exactPath
    val templateId = target.templateId ?: return null
    return templates.filter { entry ->
        entry.summary.id?.value.equals(templateId, ignoreCase = true)
    }.singleOrNull()
}

internal data class SiblingMove(val destination: Path, val placement: EntryPlacement)

internal enum class MoveDirection { UP, DOWN }

internal fun siblingMove(
    snapshot: LibrarySnapshot,
    source: LibraryTreeSelection,
    direction: MoveDirection,
): SiblingMove? {
    fun findParent(parent: Path, children: List<LibraryEntry>): Pair<Path, List<LibraryEntry>>? {
        if (children.any { it.directory == source.directory }) return parent to children
        return children.asSequence()
            .filterIsInstance<LibraryEntry.Folder>()
            .mapNotNull { findParent(it.directory, it.children) }
            .firstOrNull()
    }

    val (parent, siblings) = findParent(snapshot.root, snapshot.children) ?: return null
    val sameKind = siblings.filter {
        when (source) {
            is LibraryTreeSelection.Folder -> it is LibraryEntry.Folder
            is LibraryTreeSelection.Template -> it is LibraryEntry.Template
            is LibraryTreeSelection.Root -> false
        }
    }
    val index = sameKind.indexOfFirst { it.directory == source.directory }
    if (index < 0) return null
    return when (direction) {
        MoveDirection.UP -> sameKind.getOrNull(index - 1)?.let {
            SiblingMove(parent, EntryPlacement.Before(it.directory))
        }
        MoveDirection.DOWN -> sameKind.getOrNull(index + 1)?.let {
            SiblingMove(parent, EntryPlacement.After(it.directory))
        }
    }
}

/** The portable relative paths of every ancestor folder of [portablePath], nearest the root first. */
internal fun ancestorPortablePaths(portablePath: String): List<String> =
    portablePath.split('/').dropLast(1).runningReduce { ancestor, segment -> "$ancestor/$segment" }

internal fun remapExpandedPaths(paths: Collection<String>, oldPrefix: String, newPrefix: String): List<String> =
    paths.map { path ->
        when {
            path == oldPrefix -> newPrefix
            path.startsWith("$oldPrefix/") -> newPrefix + path.removePrefix(oldPrefix)
            else -> path
        }
    }.distinct()
