package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.StoredTemplate
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
    if (actual == null || expected.folder != actual.folder) return false
    if (expected.folder) return expected.relativePath != null && expected.relativePath == actual.relativePath
    return if (expected.templateId != null) {
        expected.templateId.equals(actual.templateId, ignoreCase = true)
    } else {
        expected.relativePath != null && expected.relativePath == actual.relativePath
    }
}

/** Mutable author context kept separate from the editor component so callbacks use the current library. */
internal data class AuthorSessionState(
    var existing: StoredTemplate?,
    var existingTarget: TemplateDetailTarget?,
    var selectionBefore: LibrarySelectionKey?,
    var destination: Path,
) {
    fun rebaseAsNewTemplate(newRoot: Path): Boolean {
        val normalizedRoot = newRoot.toAbsolutePath().normalize()
        val changed = existing != null ||
            existingTarget != null ||
            selectionBefore != null ||
            destination.toAbsolutePath().normalize() != normalizedRoot
        existing = null
        existingTarget = null
        selectionBefore = null
        destination = normalizedRoot
        return changed
    }
}

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

    @Synchronized
    fun invalidate() {
        generation.incrementAndGet()
        saveInProgress = null
    }

    fun isCurrent(request: AuthorAsyncRequest): Boolean = request.generation == generation.get()
}
