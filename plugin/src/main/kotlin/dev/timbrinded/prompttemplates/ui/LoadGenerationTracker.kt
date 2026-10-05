package dev.timbrinded.prompttemplates.ui

import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Path

internal enum class TemplateDetailIntent { USE, EDIT, DUPLICATE }

internal data class TemplateDetailTarget(
    val directory: Path,
    val templateId: String?,
)

internal data class TemplateDetailRequest(
    val generation: Int,
    val target: TemplateDetailTarget,
    val intent: TemplateDetailIntent,
)

/**
 * Keeps background library scans independent from detail loads while rejecting stale results in each channel.
 * It also knows which library the newest scan read, so results of requests made against an earlier library
 * root are recognised as stale.
 */
internal class LoadGenerationTracker {
    private val library = AtomicInteger()
    private val detail = AtomicInteger()
    @Volatile
    private var libraryRoot: Path? = null
    @Volatile
    private var pendingDetail: TemplateDetailRequest? = null

    /** Starts a scan of [root]; it supersedes every earlier scan, of this library or of another one. */
    @Synchronized
    fun beginLibraryLoad(root: Path): Int {
        libraryRoot = root.toAbsolutePath().normalize()
        return library.incrementAndGet()
    }

    /** Only the newest scan may replace the view, and only as a scan of the library that scan was started for. */
    @Synchronized
    fun acceptLibraryLoad(generation: Int, scannedRoot: Path): Boolean =
        generation == library.get() && isCurrentLibraryRoot(scannedRoot)

    /** Whether work started against [requestRoot] still targets the library the newest scan reads. */
    fun isCurrentLibraryRoot(requestRoot: Path): Boolean =
        libraryRoot?.let { current -> !hasLibraryRootChanged(requestRoot, current) } ?: false

    @Synchronized
    fun beginDetailLoad(target: TemplateDetailTarget, intent: TemplateDetailIntent): TemplateDetailRequest =
        TemplateDetailRequest(detail.incrementAndGet(), target, intent).also { pendingDetail = it }

    @Synchronized
    fun invalidateDetailLoad() {
        detail.incrementAndGet()
        pendingDetail = null
    }

    fun pendingDetailLoad(): TemplateDetailRequest? = pendingDetail

    @Synchronized
    fun acceptDetailLoad(request: TemplateDetailRequest): Boolean {
        if (request.generation != detail.get() || pendingDetail != request) return false
        pendingDetail = null
        return true
    }
}
