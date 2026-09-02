package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.EntryPlacement
import dev.timbrinded.prompttemplates.core.LibraryEntry
import dev.timbrinded.prompttemplates.core.LibrarySnapshot
import dev.timbrinded.prompttemplates.core.TemplateHealth
import java.nio.file.Path

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

internal fun countFolders(entries: List<LibraryEntry>): Int = flattenFolders(entries).size

internal fun shouldMoveToFolder(source: Path, destination: Path): Boolean = source.parent != destination

internal fun libraryDiagnostic(snapshot: LibrarySnapshot): String? = snapshot.diagnostic?.takeIf(String::isNotBlank)

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

internal fun siblingMove(
    snapshot: LibrarySnapshot,
    source: Path,
    folder: Boolean,
    direction: Int,
): SiblingMove? {
    fun findParent(parent: Path, children: List<LibraryEntry>): Pair<Path, List<LibraryEntry>>? {
        if (children.any { it.directory == source }) return parent to children
        return children.asSequence()
            .filterIsInstance<LibraryEntry.Folder>()
            .mapNotNull { findParent(it.directory, it.children) }
            .firstOrNull()
    }

    val (parent, siblings) = findParent(snapshot.root, snapshot.children) ?: return null
    val sameKind = siblings.filter { (it is LibraryEntry.Folder) == folder }
    val index = sameKind.indexOfFirst { it.directory == source }
    if (index < 0) return null
    return when {
        direction < 0 && index > 0 -> SiblingMove(parent, EntryPlacement.Before(sameKind[index - 1].directory))
        direction > 0 && index < sameKind.lastIndex -> SiblingMove(parent, EntryPlacement.After(sameKind[index + 1].directory))
        else -> null
    }
}

internal fun remapExpandedPaths(paths: Collection<String>, oldPrefix: String, newPrefix: String): List<String> =
    paths.map { path ->
        when {
            path == oldPrefix -> newPrefix
            path.startsWith("$oldPrefix/") -> newPrefix + path.removePrefix(oldPrefix)
            else -> path
        }
    }.distinct()
