package dev.timbrinded.prompttemplates.ui

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** Retains a mutation's target selection across reloads until the rebuilt tree can select it. */
internal class PreferredLibrarySelectionTracker {
    private var pending: LibrarySelectionKey? = null

    @Synchronized
    fun remember(selection: LibrarySelectionKey) {
        pending = selection
    }

    @Synchronized
    fun preferredOr(fallback: LibrarySelectionKey?): LibrarySelectionKey? = pending ?: fallback

    @Synchronized
    fun acknowledge(actual: LibrarySelectionKey?) {
        val expected = pending ?: return
        if (matchesLibrarySelection(expected, actual)) pending = null
    }

    @Synchronized
    fun cancel() {
        pending = null
    }

    @Synchronized
    fun pendingSelection(): LibrarySelectionKey? = pending
}

internal fun matchesLibrarySelection(expected: LibrarySelectionKey, actual: LibrarySelectionKey?): Boolean {
    actual ?: return false
    return when (expected) {
        is LibrarySelectionKey.Folder -> actual is LibrarySelectionKey.Folder &&
            expected.relativePath == actual.relativePath
        is LibrarySelectionKey.Template -> actual is LibrarySelectionKey.Template &&
            expected.templateId.equals(actual.templateId, ignoreCase = true)
        is LibrarySelectionKey.TemplatePath -> actual is LibrarySelectionKey.TemplatePath &&
            expected.relativePath == actual.relativePath
    }
}

internal fun selectLibrarySelectionAfterReload(
    authorOpen: Boolean,
    currentSelection: LibrarySelectionKey?,
    activeSelection: LibrarySelectionKey?,
    persistedTemplateId: String?,
): LibrarySelectionKey? = if (authorOpen) {
    null
} else {
    currentSelection ?: activeSelection ?: persistedTemplateId?.let(LibrarySelectionKey::Template)
}

internal fun activeTemplateSelection(
    root: Path,
    activeDirectory: Path,
    templateId: String?,
): LibrarySelectionKey? = try {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val normalizedDirectory = activeDirectory.toAbsolutePath().normalize()
    if (!normalizedDirectory.startsWith(normalizedRoot)) null
    else {
        val relativePath = portablePath(normalizedRoot.relativize(normalizedDirectory))
        if (templateId == null) LibrarySelectionKey.TemplatePath(relativePath)
        else LibrarySelectionKey.Template(templateId, relativePath)
    }
} catch (_: IllegalArgumentException) {
    null
} catch (_: SecurityException) {
    null
}

internal fun hasLibraryRootChanged(previousRoot: Path, currentRoot: Path): Boolean = try {
    previousRoot.toAbsolutePath().normalize() != currentRoot.toAbsolutePath().normalize()
} catch (_: SecurityException) {
    true
}

internal fun shouldReloadSelectedDetail(
    reloadRequested: Boolean,
    authorOpen: Boolean,
    selectedDirectory: Path?,
    activeDirectory: Path?,
): Boolean = reloadRequested && !authorOpen && selectedDirectory != null && selectedDirectory == activeDirectory

internal fun shouldReloadHiddenActiveDetail(
    reloadRequested: Boolean,
    authorOpen: Boolean,
    selectedDirectory: Path?,
    activeDirectory: Path?,
): Boolean = reloadRequested && !authorOpen && selectedDirectory == null && activeDirectory != null

internal fun shouldRestartPendingDetailAfterReload(
    resolvedPendingDirectory: Path?,
    selectedTemplateDirectory: Path?,
    authorOpen: Boolean,
): Boolean = !authorOpen &&
    resolvedPendingDirectory != null &&
    resolvedPendingDirectory == selectedTemplateDirectory

internal data class AuthorAsyncRequest(
    val generation: Int,
    val destination: Path,
)

/** Rejects import/save callbacks after a newer author action and carries the request's own destination. */
internal class AuthorAsyncRequestTracker {
    private val generation = AtomicInteger()
    @Volatile
    private var saveInProgress: Int? = null

    fun begin(destination: Path): AuthorAsyncRequest = AuthorAsyncRequest(generation.incrementAndGet(), destination)

    @Synchronized
    fun beginSave(destination: Path): AuthorAsyncRequest? {
        if (saveInProgress != null) return null
        return AuthorAsyncRequest(generation.incrementAndGet(), destination).also {
            saveInProgress = it.generation
        }
    }

    @Synchronized
    fun finishSave(request: AuthorAsyncRequest) {
        if (saveInProgress == request.generation) saveInProgress = null
    }

    fun isSaveInProgress(): Boolean = saveInProgress != null

    @Synchronized
    fun invalidate() {
        generation.incrementAndGet()
        saveInProgress = null
    }

    fun isCurrent(request: AuthorAsyncRequest): Boolean = request.generation == generation.get()
}
