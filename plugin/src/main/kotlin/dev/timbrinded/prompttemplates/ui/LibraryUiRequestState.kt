package dev.timbrinded.prompttemplates.ui

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

internal fun hasLibraryRootChanged(previousRoot: Path, currentRoot: Path): Boolean = try {
    previousRoot.toAbsolutePath().normalize() != currentRoot.toAbsolutePath().normalize()
} catch (_: SecurityException) {
    true
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

    fun isSaveInProgress(): Boolean = saveInProgress != null

    @Synchronized
    fun invalidate() {
        generation.incrementAndGet()
        saveInProgress = null
    }

    fun isCurrent(request: AuthorAsyncRequest): Boolean = request.generation == generation.get()
}
